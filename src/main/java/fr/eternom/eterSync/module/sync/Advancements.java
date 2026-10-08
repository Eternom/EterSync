package fr.eternom.eterSync.module.sync;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Succès (advancements) retirés du serveur. Sur un réseau, chaque serveur a ses propres succès : en les redonnant à
 * l'arrivée, le jeu rejoue toutes les notifications et leurs sons, et le serveur ne peut pas les cacher. Les recettes du
 * livre de recettes (recipes/..., des succès cachés) restent.
 */
public final class Advancements {

    private Advancements() {
    }

    /** Au démarrage, avant l'arrivée des joueurs (chaque retrait renvoie les succès aux joueurs connectés). */
    public static void disable(Logger logger) {
        List<NamespacedKey> keys = new ArrayList<>();
        Bukkit.advancementIterator().forEachRemaining((Advancement advancement) -> {
            if (!advancement.getKey().getKey().startsWith("recipes/")) {
                keys.add(advancement.getKey());
            }
        });
        int removed = (int) keys.stream().filter(key -> Bukkit.getUnsafe().removeAdvancement(key)).count();
        logger.info("Succès désactivés (" + removed + " retirés) : plus de notifications en changeant de serveur");
    }
}
