package fr.eternom.eterSync.module.sync;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterSync.module.sync.SnapshotRepository.Reason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Synchronisation de l'état des joueurs entre les serveurs d'un même groupe.
 *
 * Le danger : en changeant de serveur, l'arrivée sur B peut être traitée avant que A ait fini d'enregistrer.
 * D'où un verrou Redis par joueur et par groupe, tenu par le serveur où il joue. Velocity connecte le joueur à B
 * AVANT de le déconnecter de A : B ne peut donc pas attendre pendant la connexion (A attendrait le départ du joueur,
 * qui attendrait B). D'où, sur B :
 * <ol>
 *     <li>arrivée : le joueur est figé (aucune action possible) ; en tâche de fond, attendre que A ait enregistré et
 *     libéré le verrou, le prendre, lire la dernière sauvegarde ;</li>
 *     <li>chargement : appliquer la sauvegarde, ou, s'il n'en a jamais eu, garder son inventaire actuel comme première
 *     sauvegarde — on ne vide JAMAIS un inventaire — puis libérer le joueur ;</li>
 *     <li>départ : enregistrer, PUIS libérer le verrou.</li>
 * </ol>
 * Un joueur dont l'état n'a pas pu être chargé n'est jamais enregistré : son inventaire local, non synchronisé,
 * écraserait le vrai. Au-delà de lockWait sans pouvoir charger, il est renvoyé avec un message.
 */
public class SyncService {

    /** Durée de vie du verrou, rafraîchi tant que le joueur est là : un serveur planté ne bloque pas le joueur longtemps. */
    public static final Duration LOCK_TTL = Duration.ofSeconds(60);
    private static final long WAIT_STEP_MILLIS = 100;

    private final JavaPlugin plugin;
    private final SnapshotRepository repository;
    private final RedisCache redis;
    private final Messages messages;
    private final String group;
    private final String serverName;
    private final Duration lockWait;

    /** Joueurs connectés dont l'état n'est pas encore chargé : figés par {@link SyncListener}. */
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
    /** Joueurs dont l'état est bien celui synchronisé : les seuls qu'on a le droit d'enregistrer. */
    private final Set<UUID> synced = ConcurrentHashMap.newKeySet();
    /**
     * Pour chaque joueur chargé : les serveurs dont le coffre de l'Ender local a déjà été versé dans le coffre synchronisé.
     * Gardé DANS la sauvegarde (même écriture que le coffre) : jamais « versé » sans que le coffre versé soit enregistré.
     */
    private final Map<UUID, Set<String>> enderServers = new ConcurrentHashMap<>();
    /** Joueurs dont la sauvegarde de départ est en cours sur ce serveur. */
    private final Set<UUID> saving = ConcurrentHashMap.newKeySet();
    /**
     * Toutes les écritures passent par ce fil unique, dans l'ordre où elles sont demandées : une sauvegarde plus
     * ancienne ne peut jamais finir après une plus récente (et devenir « la dernière » par erreur).
     */
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "EterSync-writer"));

    public SyncService(JavaPlugin plugin, SnapshotRepository repository, RedisCache redis, Messages messages,
                       String group, String serverName, Duration lockWait) {
        this.plugin = plugin;
        this.repository = repository;
        this.redis = redis;
        this.messages = messages;
        this.group = group;
        this.serverName = serverName;
        this.lockWait = lockWait;
    }

    /** Arrivée (thread principal) : fige le joueur et lance le chargement en tâche de fond. */
    public void join(Player player) {
        UUID uuid = player.getUniqueId();
        loading.add(uuid);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<PlayerSnapshot> snapshot;
            try {
                if (!acquire(uuid)) {
                    plugin.getLogger().warning("Verrou de synchronisation toujours pris pour " + player.getName() + " : renvoyé");
                    kick(player, "sync.busy");
                    return;
                }
                snapshot = repository.latest(uuid, group);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Chargement impossible pour " + player.getName(), e);
                release(uuid);
                kick(player, "sync.error");
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> finishJoin(player, snapshot));
        });
    }

    /** Thread principal : applique la sauvegarde, ou garde l'inventaire actuel comme première sauvegarde, puis libère le joueur. */
    private void finishJoin(Player player, Optional<PlayerSnapshot> snapshot) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || !loading.remove(uuid)) {
            write(() -> release(uuid)); // parti pendant le chargement : rien à appliquer ni à enregistrer
            return;
        }
        if (snapshot.isPresent()) {
            snapshot.get().apply(player);
            boolean merged = applyEnderChest(player, snapshot.get());
            synced.add(uuid);
            if (merged) {
                // Le coffre local vient d'être versé : enregistré tout de suite, avec ce serveur dans la liste
                PlayerSnapshot current = capture(player);
                write(() -> save(uuid, Reason.ENDER_MERGE, current));
            }
        } else {
            enderServers.put(uuid, new HashSet<>(Set.of(serverName)));
            synced.add(uuid);
            PlayerSnapshot current = capture(player);
            write(() -> save(uuid, Reason.FIRST, current));
        }
    }

    /**
     * Coffre de l'Ender à l'arrivée. Chaque serveur avait jusqu'ici son propre coffre local : au premier passage d'un
     * joueur sur un serveur, ce coffre local est VERSÉ dans le coffre synchronisé (rien n'est jamais vidé), et le serveur
     * est noté dans la sauvegarde ; ensuite, le coffre synchronisé remplace le local. Ce qui ne rentre pas va dans
     * l'inventaire, puis au sol. Une sauvegarde d'avant ce format (coffre inconnu) : le coffre local devient la référence.
     * @return true si la sauvegarde doit être réécrite tout de suite (coffre versé ou devenu la référence)
     */
    private boolean applyEnderChest(Player player, PlayerSnapshot snapshot) {
        Set<String> servers = new HashSet<>(snapshot.enderServers());
        enderServers.put(player.getUniqueId(), servers);
        Inventory chest = player.getEnderChest();
        if (snapshot.enderChest() == null) {
            servers.add(serverName);
            return true;
        }
        if (servers.contains(serverName)) {
            chest.setContents(Arrays.copyOf(PlayerSnapshot.clone(snapshot.enderChest()), chest.getSize()));
            return false;
        }
        ItemStack[] local = PlayerSnapshot.clone(chest.getContents());
        chest.setContents(Arrays.copyOf(PlayerSnapshot.clone(snapshot.enderChest()), chest.getSize()));
        for (ItemStack item : local) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            chest.addItem(item).values().forEach(left -> player.getInventory().addItem(left).values()
                    .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest)));
        }
        servers.add(serverName);
        plugin.getLogger().info("Coffre de l'Ender local de " + player.getName() + " versé dans son coffre synchronisé");
        return true;
    }

    private PlayerSnapshot capture(Player player) {
        return PlayerSnapshot.capture(player, enderServers.getOrDefault(player.getUniqueId(), Set.of(serverName)));
    }

    /** Attend (bloquant) que le verrou soit libre, puis le prend. @return false si toujours pris après lockWait */
    private boolean acquire(UUID player) {
        long deadline = System.currentTimeMillis() + lockWait.toMillis();
        while (saving.contains(player) || !redis.setIfAbsent(lockKey(player), serverName, LOCK_TTL)) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(WAIT_STEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private void kick(Player player, String key) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            loading.remove(player.getUniqueId());
            if (player.isOnline()) {
                player.kick(messages.get(player, key));
            }
        });
    }

    /** true tant que l'état du joueur n'est pas chargé : toute action doit être bloquée. */
    public boolean isLoading(UUID player) {
        return loading.contains(player);
    }

    /** Départ (thread principal) : enregistre, puis libère le verrou. À l'arrêt du serveur, tout se fait tout de suite. */
    public void quit(Player player) {
        UUID uuid = player.getUniqueId();
        loading.remove(uuid); // si le chargement finit après, finishJoin rendra le verrou
        if (!synced.remove(uuid)) {
            enderServers.remove(uuid);
            // Jamais chargé : rien à enregistrer. Ne rend le verrou que s'il est à ce serveur (deleteIfValue).
            write(() -> release(uuid));
            return;
        }
        PlayerSnapshot snapshot = capture(player);
        enderServers.remove(uuid);
        saving.add(uuid);
        Runnable task = () -> {
            try {
                save(uuid, Bukkit.isStopping() ? Reason.SHUTDOWN : Reason.QUIT, snapshot);
            } finally {
                release(uuid);
                saving.remove(uuid);
            }
        };
        if (Bukkit.isStopping()) {
            writeAndWait(task); // pendant l'arrêt, on attend : le serveur ne doit pas s'éteindre avant
        } else {
            write(task);
        }
    }

    /** Issue d'une restauration, pour le message au staff. server : le serveur où est le joueur (ELSEWHERE). */
    public record RestoreResult(Outcome outcome, String server) {
        public enum Outcome { RESTORED_ONLINE, RESTORED_OFFLINE, ELSEWHERE, BUSY, GONE, ERROR }

        static RestoreResult of(Outcome outcome) {
            return new RestoreResult(outcome, null);
        }
    }

    /**
     * Remet une ancienne sauvegarde (staff, thread principal ; done est appelé sur le thread principal). Seul le serveur
     * qui tient le verrou du joueur a le droit de toucher à son état :
     * <ul>
     *     <li>connecté ici : appliquée tout de suite, son état d'avant gardé dans l'historique (BEFORE_RESTORE) ;</li>
     *     <li>connecté nulle part : ce serveur prend le verrou (le joueur qui arrive attend), écrit la sauvegarde
     *     restaurée comme la dernière, puis rend le verrou : il la retrouve à sa connexion ;</li>
     *     <li>connecté sur un autre serveur du groupe : refusé (ELSEWHERE), à faire depuis ce serveur-là.</li>
     * </ul>
     */
    public void restore(UUID target, long snapshotId, String staff, Consumer<RestoreResult> done) {
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            restoreOnline(online, snapshotId, staff, done);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RestoreResult result;
            try {
                result = restoreOffline(target, snapshotId, staff);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Restauration impossible pour " + target, e);
                result = RestoreResult.of(RestoreResult.Outcome.ERROR);
            }
            RestoreResult finalResult = result;
            Bukkit.getScheduler().runTask(plugin, () -> done.accept(finalResult));
        });
    }

    private void restoreOnline(Player player, long snapshotId, String staff, Consumer<RestoreResult> done) {
        UUID uuid = player.getUniqueId();
        if (!synced.contains(uuid)) {
            done.accept(RestoreResult.of(RestoreResult.Outcome.BUSY)); // encore en chargement
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<PlayerSnapshot> old;
            try {
                old = repository.load(snapshotId, uuid, group);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Restauration impossible pour " + player.getName(), e);
                Bukkit.getScheduler().runTask(plugin, () -> done.accept(RestoreResult.of(RestoreResult.Outcome.ERROR)));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (old.isEmpty()) {
                    done.accept(RestoreResult.of(RestoreResult.Outcome.GONE));
                    return;
                }
                if (!player.isOnline() || !synced.contains(uuid)) {
                    done.accept(RestoreResult.of(RestoreResult.Outcome.BUSY)); // parti entre-temps : sauvegarde de départ en cours
                    return;
                }
                // Fermer d'abord : l'item tenu au curseur et la grille d'artisanat reviennent dans l'inventaire, et sont
                // donc dans la sauvegarde « avant » au lieu de s'ajouter à l'inventaire restauré (duplication)
                player.closeInventory();
                PlayerSnapshot before = capture(player);
                PlayerSnapshot restored = old.get().restoredOnto(before);
                restored.apply(player);
                Inventory chest = player.getEnderChest();
                chest.setContents(Arrays.copyOf(PlayerSnapshot.clone(restored.enderChest()), chest.getSize()));
                PlayerSnapshot after = capture(player);
                write(() -> save(uuid, Reason.BEFORE_RESTORE, before));
                write(() -> save(uuid, Reason.RESTORE, after));
                plugin.getLogger().info(staff + " a restauré la sauvegarde #" + snapshotId + " de " + player.getName()
                        + " (connecté ici, état d'avant gardé dans l'historique)");
                messages.send(player, "restore.notify");
                done.accept(RestoreResult.of(RestoreResult.Outcome.RESTORED_ONLINE));
            });
        });
    }

    /** Asynchrone : le joueur n'est pas sur ce serveur. */
    private RestoreResult restoreOffline(UUID target, long snapshotId, String staff) {
        if (saving.contains(target)) {
            return RestoreResult.of(RestoreResult.Outcome.BUSY);
        }
        String key = lockKey(target);
        if (!redis.setIfAbsent(key, serverName, LOCK_TTL)) {
            Optional<String> holder = redis.get(key);
            return holder.isPresent() && !holder.get().equals(serverName)
                    ? new RestoreResult(RestoreResult.Outcome.ELSEWHERE, holder.get())
                    : RestoreResult.of(RestoreResult.Outcome.BUSY);
        }
        try {
            // Par le fil d'écriture, comme toute écriture : rien d'ancien ne peut passer après
            return writer.submit(() -> {
                Optional<PlayerSnapshot> old = repository.load(snapshotId, target, group);
                if (old.isEmpty()) {
                    return RestoreResult.of(RestoreResult.Outcome.GONE);
                }
                PlayerSnapshot current = repository.latest(target, group).orElse(old.get());
                // Pas de copie de secours ici : en cas d'échec, rien n'a changé, le staff le voit et réessaie
                repository.save(target, group, serverName, Reason.RESTORE, old.get().restoredOnto(current));
                plugin.getLogger().info(staff + " a restauré la sauvegarde #" + snapshotId + " de " + target
                        + " (hors ligne, appliquée à sa prochaine connexion)");
                return RestoreResult.of(RestoreResult.Outcome.RESTORED_OFFLINE);
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RestoreResult.of(RestoreResult.Outcome.ERROR);
        } catch (ExecutionException | java.util.concurrent.RejectedExecutionException e) {
            plugin.getLogger().log(Level.SEVERE, "Restauration impossible pour " + target, e.getCause() != null ? e.getCause() : e);
            return RestoreResult.of(RestoreResult.Outcome.ERROR);
        } finally {
            release(target);
        }
    }

    /** Toutes les quelques minutes (thread principal) : protège contre la perte d'un plantage du serveur. */
    public void autosave() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (synced.contains(player.getUniqueId())) {
                PlayerSnapshot snapshot = capture(player);
                UUID uuid = player.getUniqueId();
                write(() -> save(uuid, Reason.AUTOSAVE, snapshot));
            }
        }
    }

    /** Régulièrement (asynchrone) : le verrou reste à ce serveur tant que le joueur y est. */
    public void refreshLocks() {
        synced.forEach(uuid -> redis.expire(lockKey(uuid), LOCK_TTL));
    }

    /** Arrêt du plugin : enregistre tout le monde tout de suite et rend les verrous. */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (synced.remove(uuid)) {
                PlayerSnapshot snapshot = capture(player);
                writeAndWait(() -> {
                    save(uuid, Reason.SHUTDOWN, snapshot);
                    release(uuid);
                });
            }
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Des sauvegardes n'ont pas pu se terminer avant l'arrêt");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public String getGroup() {
        return group;
    }

    public SnapshotRepository getRepository() {
        return repository;
    }

    /**
     * Enregistre en base ; en cas d'échec, écrit la sauvegarde dans plugins/EterSync/failed/ pour qu'elle ne soit
     * jamais perdue (à restaurer à la main), et le signale dans la console.
     */
    private void save(UUID player, Reason reason, PlayerSnapshot snapshot) {
        try {
            repository.save(player, group, serverName, reason, snapshot);
        } catch (RuntimeException e) {
            File folder = new File(plugin.getDataFolder(), "failed");
            File file = new File(folder, player + "-" + System.currentTimeMillis() + ".bin");
            try {
                Files.createDirectories(folder.toPath());
                Files.write(file.toPath(), snapshot.toBytes());
                plugin.getLogger().log(Level.SEVERE, "Sauvegarde en base impossible pour " + player
                        + " : copie de secours dans " + file.getPath(), e);
            } catch (IOException io) {
                plugin.getLogger().log(Level.SEVERE, "Sauvegarde ET copie de secours impossibles pour " + player, io);
            }
        }
    }

    private void write(Runnable task) {
        if (writer.isShutdown()) {
            task.run(); // plugin déjà arrêté (déconnexions après l'arrêt) : on écrit tout de suite
        } else {
            writer.execute(task);
        }
    }

    private void writeAndWait(Runnable task) {
        if (writer.isShutdown()) {
            task.run();
            return;
        }
        try {
            writer.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            plugin.getLogger().log(Level.SEVERE, "Sauvegarde impossible pendant l'arrêt", e.getCause());
        }
    }

    private void release(UUID player) {
        try {
            redis.deleteIfValue(lockKey(player), serverName);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Verrou non libéré pour " + player + " (il expirera seul)", e);
        }
    }

    private String lockKey(UUID player) {
        return "sync:lock:" + group + ":" + player;
    }
}
