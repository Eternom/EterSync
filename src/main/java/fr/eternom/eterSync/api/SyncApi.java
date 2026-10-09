package fr.eternom.eterSync.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * Ce qu'EterSync offre aux autres plugins : savoir si l'inventaire d'un joueur est en cours de chargement (n'y touchez
 * pas), lire son dernier inventaire enregistré, et l'historique en lecture seule (staff). Personne d'autre ne lit
 * etersync_snapshots : on demande ici. Jamais d'écriture : seul EterSync déplace des objets (sinon, duplication).
 * <pre>
 *     // compileOnly("com.github.Eternom:EterSync:&lt;tag&gt;") ; plugin.yml : softdepend: [EterSync]
 * </pre>
 */
public interface SyncApi {

    /** L'API d'EterSync si le plugin tourne sur ce serveur. */
    static Optional<SyncApi> get() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(SyncApi.class));
    }

    /** true tant que l'état du joueur arrivé ici n'est pas chargé : aucun plugin ne doit toucher à son inventaire. */
    boolean isLoading(UUID player);

    /**
     * Le dernier inventaire enregistré de ce groupe de serveurs (41 cases : inventaire, armure, seconde main), copie en
     * lecture seule ; vide s'il n'y en a pas. Pour un joueur connecté, c'est sa dernière sauvegarde (départ ou toutes
     * les autosave-minutes), pas son inventaire de l'instant. Bloquant (base) : hors du thread principal.
     */
    Optional<ItemStack[]> lastInventory(UUID player);

    /** L'historique des sauvegardes d'un joueur, en lecture seule (etersync.admin). Thread principal. */
    void openHistory(Player viewer, String targetName);
}
