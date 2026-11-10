package fr.eternom.eterSync.module.history;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.gui.Dialogs;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import fr.eternom.eterSync.module.sync.SnapshotRepository.Entry;
import fr.eternom.eterSync.module.sync.SyncService;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Historique des sauvegardes d'un joueur (etersync.admin) : liste et aperçu de l'inventaire, en lecture seule.
 * Restauration à part (etersync.restore, confirmation) : un joueur pourrait donner ses items puis demander à les
 * récupérer (duplication), c'est au staff de vérifier avant ; l'état remplacé reste dans l'historique.
 * Toutes les méthodes s'appellent sur le thread principal ; la base est lue en asynchrone.
 */
public class HistoryGui {

    /** Restaurer une sauvegarde : séparée de etersync.admin (voir l'historique), à ne donner qu'au staff de confiance. */
    public static final String RESTORE_PERMISSION = "etersync.restore";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final SyncService sync;
    private final PlayerDirectory players;
    private final Messages messages;
    private final BackButton backButton;

    public HistoryGui(JavaPlugin plugin, SyncService sync, PlayerDirectory players, Messages messages, BackButton backButton) {
        this.plugin = plugin;
        this.sync = sync;
        this.players = players;
        this.messages = messages;
        this.backButton = backButton;
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
                        viewer.openInventory(new HistoryMenu(this, viewer, target.get().name(), target.get().entries()).getInventory());
                    }
                });
    }

    void preview(Player viewer, String targetName, Entry entry) {
        async(viewer, () -> sync.getRepository().load(entry.id()), snapshot -> snapshot.ifPresentOrElse(
                found -> viewer.openInventory(new PreviewMenu(this, viewer, targetName, entry, found).getInventory()),
                () -> messages.send(viewer, "history.gone")));
    }

    /** Fenêtre de confirmation, puis restauration ; Annuler rouvre l'aperçu. */
    void confirmRestore(Player viewer, String targetName, Entry entry) {
        viewer.closeInventory();
        DialogBase base = DialogBase.builder(messages.get(viewer, "restore.confirm.title", "player", targetName))
                .body(List.of(DialogBody.plainMessage(messages.get(viewer, "restore.confirm.body",
                        "player", targetName, "date", date(entry)))))
                .build();
        Dialogs.show(plugin, viewer, base, messages.get(viewer, "restore.confirm.button"), messages.get(viewer, "dialog.cancel"),
                response -> restore(viewer, targetName, entry), () -> preview(viewer, targetName, entry));
    }

    private void restore(Player viewer, String targetName, Entry entry) {
        if (!viewer.hasPermission(RESTORE_PERMISSION)) { // retirée pendant que la fenêtre était ouverte
            messages.send(viewer, "restore.no-permission");
            return;
        }
        sync.restore(entry.player(), entry.id(), viewer.getName(), result -> {
            String[] placeholders = {"player", targetName, "date", date(entry)};
            switch (result.outcome()) {
                case RESTORED_ONLINE -> {
                    Sounds.click(viewer);
                    messages.send(viewer, "restore.done-online", placeholders);
                }
                case RESTORED_OFFLINE -> {
                    Sounds.click(viewer);
                    messages.send(viewer, "restore.done-offline", placeholders);
                }
                case ELSEWHERE -> messages.send(viewer, "restore.elsewhere", "player", targetName,
                        "server", EterLib.get().getServerDisplayName(result.server()));
                case BUSY -> messages.send(viewer, "restore.busy", placeholders);
                case GONE -> messages.send(viewer, "history.gone");
                case ERROR -> messages.send(viewer, "sync.error-generic");
            }
        });
    }

    String date(Entry entry) {
        return DATE.format(Instant.ofEpochMilli(entry.createdAt()));
    }

    Messages messages() {
        return messages;
    }

    BackButton backButton() {
        return backButton;
    }

    private <T> void async(Player player, Supplier<T> task, Consumer<T> then) {
        Tasks.async(plugin, player, task, then, () -> messages.send(player, "sync.error-generic"));
    }

    private record Target(UUID uuid, String name, List<Entry> entries) {
    }
}
