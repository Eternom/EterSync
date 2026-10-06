package fr.eternom.eterSync.module.sync;

import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterSync.module.sync.SnapshotRepository.Reason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        } else {
            PlayerSnapshot current = PlayerSnapshot.capture(player);
            write(() -> save(uuid, Reason.FIRST, current));
        }
        synced.add(uuid);
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
            // Jamais chargé : rien à enregistrer. Ne rend le verrou que s'il est à ce serveur (deleteIfValue).
            write(() -> release(uuid));
            return;
        }
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
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

    /** Toutes les quelques minutes (thread principal) : protège contre la perte d'un plantage du serveur. */
    public void autosave() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (synced.contains(player.getUniqueId())) {
                PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
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
                PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
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
