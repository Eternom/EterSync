package fr.eternom.eterSync.module.sync;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * Arrivée, départ, et gel du joueur tant que son état n'est pas chargé : il ne doit rien pouvoir faire
 * avec son inventaire local, qui va être remplacé (sinon : duplication ou perte d'items).
 */
public class SyncListener implements Listener {

    private final SyncService sync;

    public SyncListener(SyncService sync) {
        this.sync = sync;
    }

    /** LOWEST : le chargement démarre avant que les autres plugins ne regardent l'inventaire. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        sync.join(event.getPlayer());
    }

    /** MONITOR : on enregistre l'état final, après ce que les autres plugins ont pu faire à la déconnexion. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sync.quit(event.getPlayer());
    }

    // ---- Gel pendant le chargement (LOWEST : annulé avant que quiconque ne réagisse) ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        freeze(event.getWhoClicked(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        freeze(event.getWhoClicked(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        freeze(event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        freeze(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        freeze(event.getDamager(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        freeze(event.getPlayer(), event);
    }

    private void freeze(Entity entity, Cancellable event) {
        if (entity instanceof Player player && sync.isLoading(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
