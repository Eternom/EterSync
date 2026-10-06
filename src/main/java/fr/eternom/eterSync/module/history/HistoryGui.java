package fr.eternom.eterSync.module.history;

import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import fr.eternom.eterSync.module.sync.PlayerSnapshot;
import fr.eternom.eterSync.module.sync.SnapshotRepository.Entry;
import fr.eternom.eterSync.module.sync.SyncService;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Historique des sauvegardes d'un joueur, pour les admins : liste, aperçu de l'inventaire, restauration.
 * Toutes les méthodes s'appellent sur le thread principal ; la base est lue en asynchrone.
 */
public class HistoryGui {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final ClickCallback.Options ONE_USE = ClickCallback.Options.builder()
            .uses(1)
            .lifetime(Duration.ofMinutes(5))
            .build();

    private final JavaPlugin plugin;
    private final SyncService sync;
    private final PlayerDirectory players;
    private final Messages messages;
    private final String serverName;

    public HistoryGui(JavaPlugin plugin, SyncService sync, PlayerDirectory players, Messages messages, String serverName) {
        this.plugin = plugin;
        this.sync = sync;
        this.players = players;
        this.messages = messages;
        this.serverName = serverName;
    }

    /** Cherche le joueur sur tout le réseau (eter_players) puis ouvre son historique dans le groupe de ce serveur. */
    public void open(Player viewer, String targetName) {
        async(viewer, () -> players.find(targetName).map(target ->
                        new Target(target.uuid(), target.name(), sync.getRepository().history(target.uuid(), sync.getGroup()))),
                target -> {
                    if (target.isEmpty()) {
                        messages.send(viewer, "history.unknown-player", "player", targetName);
                    } else if (target.get().entries().isEmpty()) {
                        messages.send(viewer, "history.empty", "player", target.get().name(), "group", sync.getGroup());
                    } else {
                        viewer.openInventory(new HistoryMenu(this, viewer, target.get().uuid(), target.get().name(),
                                target.get().entries()).getInventory());
                    }
                });
    }

    void preview(Player viewer, UUID target, String targetName, Entry entry) {
        async(viewer, () -> sync.getRepository().load(entry.id()), snapshot -> snapshot.ifPresent(found ->
                viewer.openInventory(new PreviewMenu(this, viewer, target, targetName, entry, found).getInventory())));
    }

    /** Boîte de dialogue de confirmation, puis restauration. */
    void confirmRestore(Player viewer, UUID target, String targetName, Entry entry) {
        viewer.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(viewer, "restore.title"))
                .body(List.of(DialogBody.plainMessage(messages.get(viewer, "restore.body",
                        "player", targetName, "date", date(entry)))))
                .build();
        viewer.showDialog(Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.confirmation(
                        button(viewer, "restore.confirm", () -> restore(viewer, target, targetName, entry)),
                        button(viewer, "restore.cancel", () -> open(viewer, targetName))))));
    }

    private void restore(Player viewer, UUID target, String targetName, Entry entry) {
        async(viewer, () -> new Restore(sync.getRepository().load(entry.id()),
                        players.getServer(target).filter(server -> !server.equals(serverName)).isPresent()),
                restore -> {
                    if (restore.snapshot().isEmpty()) {
                        messages.send(viewer, "history.gone");
                    } else if (!sync.restore(target, restore.snapshot().get(), restore.onlineElsewhere())) {
                        messages.send(viewer, "restore.elsewhere", "player", targetName);
                        viewer.playSound(viewer, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    } else {
                        messages.send(viewer, "restore.done", "player", targetName, "date", date(entry));
                        viewer.playSound(viewer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1f);
                    }
                });
    }

    String date(Entry entry) {
        return DATE.format(Instant.ofEpochMilli(entry.createdAt()));
    }

    Messages messages() {
        return messages;
    }

    private ActionButton button(Player player, String key, Runnable onClick) {
        return ActionButton.builder(messages.get(player, key))
                .action(DialogAction.customClick(
                        // Le clic arrive du réseau : on repasse sur le thread principal
                        (response, audience) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) {
                                onClick.run();
                            }
                        }),
                        ONE_USE))
                .build();
    }

    private <T> void async(Player player, Supplier<T> task, Consumer<T> then) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T result;
            try {
                result = task.get();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur dans l'historique de synchronisation", e);
                Bukkit.getScheduler().runTask(plugin, () -> messages.send(player, "sync.error-generic"));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    then.accept(result);
                }
            });
        });
    }

    private record Target(UUID uuid, String name, List<Entry> entries) {
    }

    private record Restore(Optional<PlayerSnapshot> snapshot, boolean onlineElsewhere) {
    }
}
