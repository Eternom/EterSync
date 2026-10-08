package fr.eternom.eterSync.module.sync;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;

/**
 * Recettes débloquées sans notification « Nouvelles recettes » : à l'arrivée sur un serveur, la synchronisation et le
 * jeu (objets déjà dans l'inventaire) en débloquent des dizaines d'un coup. Elles restent dans le livre de recettes.
 */
public class RecipeToastListener implements Listener {

    @EventHandler
    public void onDiscover(PlayerRecipeDiscoverEvent event) {
        event.shouldShowNotification(false);
    }
}
