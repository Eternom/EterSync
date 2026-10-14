package fr.eternom.eterSync.module.sync;

import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

import java.util.logging.Logger;

/**
 * Les succès de Minecraft restent invisibles : un rechargement des données (/minecraft:reload, datapack) les remet, ils
 * sont donc retirés de nouveau ; et si l'un passe quand même, son annonce dans le chat est supprimée (les succès du
 * réseau sont ceux d'EterSuccess).
 */
public class AdvancementListener implements Listener {

    private final Logger logger;

    public AdvancementListener(Logger logger) {
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        event.message(null);
    }

    @EventHandler
    public void onReload(ServerResourcesReloadedEvent event) {
        Advancements.disable(logger);
    }
}
