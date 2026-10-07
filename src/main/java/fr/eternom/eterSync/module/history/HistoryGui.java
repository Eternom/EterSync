package fr.eternom.eterSync.module.history;

import fr.eternom.eterLib.helper.gui.BackButton;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.helper.task.Tasks;
import fr.eternom.eterLib.module.player.PlayerDirectory;
import fr.eternom.eterSync.module.sync.SnapshotRepository.Entry;
import fr.eternom.eterSync.module.sync.SyncService;
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
 * Historique des sauvegardes d'un joueur, en LECTURE SEULE (admins) : liste et aperçu de l'inventaire.
 * Volontairement sans restauration : un joueur pourrait donner ses items puis demander à les récupérer (duplication).
 * Toutes les méthodes s'appellent sur le thread principal ; la base est lue en asynchrone.
 */
public class HistoryGui {

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
