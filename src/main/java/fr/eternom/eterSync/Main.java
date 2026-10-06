package fr.eternom.eterSync;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.cache.RedisCache;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterSync.listeners.Commands;
import fr.eternom.eterSync.listeners.Events;
import fr.eternom.eterSync.module.history.HistoryGui;
import fr.eternom.eterSync.module.sync.SnapshotRepository;
import fr.eternom.eterSync.module.sync.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

public final class Main extends JavaPlugin {

    /** Préfixe des tables d'EterSync dans la base commune : etersync_snapshots. */
    private static final String TABLE_PREFIX = "etersync_";
    private static final Pattern GROUP = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final long LOCK_REFRESH_TICKS = 20 * 20;

    private Messages messages;
    private SyncService sync;
    private HistoryGui history;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        EterLib lib = EterLib.get();
        messages = lib.messages(this, "en_us", "fr_fr");

        // Sans Redis, impossible de savoir si l'ancien serveur a fini d'enregistrer : risque de perte ou de duplication
        RedisCache redis = lib.getRedis();
        if (redis == null) {
            getLogger().severe("Redis est obligatoire pour EterSync : active cache.enabled dans EterLib/config.yml. Plugin désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        String group = getConfig().getString("group", "default").toLowerCase(Locale.ROOT);
        if (!GROUP.matcher(group).matches()) {
            getLogger().severe("'group' invalide dans config.yml : \"" + group + "\" (minuscules, chiffres, _ et -). Plugin désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        SnapshotRepository repository = new SnapshotRepository(lib.database(TABLE_PREFIX), getConfig().getInt("history-size", 10));
        sync = new SyncService(this, repository, redis, messages, group, lib.getServerName(),
                Duration.ofSeconds(Math.max(1, getConfig().getInt("lock-wait", 10))));
        history = new HistoryGui(this, sync, lib.getPlayers(), messages);

        new Commands(this);
        new Events(this);

        long autosave = Math.max(1, getConfig().getInt("autosave-minutes", 5)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, sync::autosave, autosave, autosave);
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, sync::refreshLocks, LOCK_REFRESH_TICKS, LOCK_REFRESH_TICKS);
        getLogger().info("Synchronisation active pour le groupe \"" + group + "\"");
    }

    @Override
    public void onDisable() {
        if (sync != null) {
            sync.shutdown();
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public SyncService getSync() {
        return sync;
    }

    public HistoryGui getHistory() {
        return history;
    }
}
