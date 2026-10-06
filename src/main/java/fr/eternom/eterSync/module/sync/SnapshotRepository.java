package fr.eternom.eterSync.module.sync;

import fr.eternom.eterLib.helper.sql.Column;
import fr.eternom.eterLib.helper.sql.Database;
import fr.eternom.eterLib.helper.sql.Row;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sauvegardes en base (table etersync_snapshots). Chaque sauvegarde est une NOUVELLE ligne, jamais une mise à jour :
 * la plus récente fait foi, les précédentes forment l'historique (les plus anciennes au-delà de historySize sont effacées).
 * Les appels sont bloquants : à exécuter hors du thread principal (sauf à l'arrêt du serveur).
 */
public class SnapshotRepository {

    private static final String TABLE = "snapshots";

    /** Pourquoi la sauvegarde a été faite, affiché dans l'historique. */
    public enum Reason { FIRST, QUIT, AUTOSAVE, SHUTDOWN, RESTORE }

    /** Une sauvegarde, sans ses données (lues seulement si on l'ouvre ou la restaure). */
    public record Entry(long id, UUID player, String group, String server, Reason reason, long createdAt) {
    }

    private final Database database;
    private final int historySize;

    public SnapshotRepository(Database database, int historySize) {
        this.database = database;
        this.historySize = Math.max(1, historySize);
        database.createTable(TABLE,
                Column.of("id", Column.Type.LONG).autoIncrement(),
                Column.of("uuid", Column.Type.UUID).notNull(),
                Column.of("sync_group", Column.Type.STRING).length(32).notNull(),
                Column.of("server", Column.Type.STRING).length(64).notNull(),
                Column.of("reason", Column.Type.STRING).length(16).notNull(),
                Column.of("created_at", Column.Type.LONG).notNull(),
                Column.of("data", Column.Type.BLOB).notNull());
    }

    public void save(UUID player, String group, String server, Reason reason, PlayerSnapshot snapshot) {
        database.insert(TABLE, Map.of("uuid", player, "sync_group", group, "server", server, "reason", reason,
                "created_at", System.currentTimeMillis(), "data", snapshot.toBytes()));
        prune(player, group);
    }

    /** La sauvegarde qui fait foi ; vide si le joueur n'a encore jamais été synchronisé dans ce groupe. */
    public Optional<PlayerSnapshot> latest(UUID player, String group) {
        return database.query("SELECT data FROM " + database.table(TABLE)
                        + " WHERE uuid = ? AND sync_group = ? ORDER BY id DESC LIMIT 1", player, group)
                .stream().findFirst().map(row -> PlayerSnapshot.fromBytes(row.getBytes("data")));
    }

    /** Historique, du plus récent au plus ancien. */
    public List<Entry> history(UUID player, String group) {
        return database.query("SELECT id, uuid, sync_group, server, reason, created_at FROM " + database.table(TABLE)
                        + " WHERE uuid = ? AND sync_group = ? ORDER BY id DESC", player, group)
                .stream().map(this::toEntry).toList();
    }

    public Optional<PlayerSnapshot> load(long id) {
        return database.getFirst(TABLE, Map.of("id", id)).map(row -> PlayerSnapshot.fromBytes(row.getBytes("data")));
    }

    /** Ne garde que les historySize sauvegardes les plus récentes du joueur dans ce groupe. */
    private void prune(UUID player, String group) {
        String table = database.table(TABLE);
        // MySQL refuse LIMIT dans un sous-select direct de IN : d'où la table dérivée "kept"
        database.execute("DELETE FROM " + table + " WHERE uuid = ? AND sync_group = ? AND id NOT IN ("
                        + "SELECT id FROM (SELECT id FROM " + table
                        + " WHERE uuid = ? AND sync_group = ? ORDER BY id DESC LIMIT ?) kept)",
                player, group, player, group, historySize);
    }

    private Entry toEntry(Row row) {
        return new Entry(row.getLong("id"), row.getUUID("uuid"), row.getString("sync_group"), row.getString("server"),
                Reason.valueOf(row.getString("reason")), row.getLong("created_at"));
    }
}
