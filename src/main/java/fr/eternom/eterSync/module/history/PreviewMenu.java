package fr.eternom.eterSync.module.history;

import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterSync.module.sync.PlayerSnapshot;
import fr.eternom.eterSync.module.sync.SnapshotRepository.Entry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Aperçu d'une sauvegarde, en lecture seule (les clics sont annulés par EterLib, aucun item ne peut être pris) :
 * <pre>
 *  ⛑ 👕 👖 👢 🛡 · · · ⓘ     armure, seconde main, infos (vie, faim, niveau, mode, effets)
 *  · · · · · · · · ·         rangées 1 à 3 : l'inventaire
 *  · · · · · · · · ·
 *  · · · · · · · · ·
 *  · · · · · · · · ·         rangée 4 : la barre d'action
 *  ◀ · · · · · · · ▣         retour ; ▣ = coffre de l'Ender (ou retour à l'inventaire)
 * </pre>
 * En mode coffre de l'Ender, les rangées 1 à 3 montrent ses 27 cases.
 */
public class PreviewMenu implements Menu {

    private static final int OFFHAND = 4;
    private static final int INFO = 8;
    private static final int BACK = 45;
    private static final int ENDER = 53;

    private final HistoryGui gui;
    private final Messages messages;
    private final Player viewer;
    private final String targetName;
    private final Entry entry;
    private final PlayerSnapshot snapshot;
    private final boolean ender;
    private final Inventory inventory;

    PreviewMenu(HistoryGui gui, Player viewer, String targetName, Entry entry, PlayerSnapshot snapshot) {
        this(gui, viewer, targetName, entry, snapshot, false);
    }

    private PreviewMenu(HistoryGui gui, Player viewer, String targetName, Entry entry, PlayerSnapshot snapshot, boolean ender) {
        this.entry = entry;
        this.snapshot = snapshot;
        this.ender = ender;
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.targetName = targetName;
        this.inventory = Bukkit.createInventory(this, 54,
                messages.get(viewer, "preview.title", "player", targetName, "date", gui.date(entry)));

        // Contenu Paper : 0-8 barre d'action, 9-35 inventaire, 36-39 armure (bottes -> casque), 40 seconde main
        ItemStack[] items = snapshot.inventory();
        if (ender) {
            // Le coffre de l'Ender : 27 cases dans les rangées 1 à 3
            ItemStack[] chest = snapshot.enderChest();
            for (int i = 0; i < 27; i++) {
                inventory.setItem(9 + i, item(chest, i));
            }
            inventory.setItem(ENDER, Items.item(Material.CHEST, text("preview.inventory"), List.of()));
            inventory.setItem(BACK, Items.item(Material.ARROW, text("preview.back"), List.of()));
            return;
        }
        for (int i = 0; i < 4; i++) {
            inventory.setItem(i, item(items, 39 - i));
        }
        inventory.setItem(OFFHAND, item(items, 40));
        for (int i = 9; i <= 35; i++) {
            inventory.setItem(i, item(items, i));
        }
        for (int i = 0; i <= 8; i++) {
            inventory.setItem(36 + i, item(items, i));
        }

        inventory.setItem(INFO, Items.item(Material.BOOK, text("preview.info.title"), List.of(
                text("preview.info.health", "value", String.valueOf(Math.round(snapshot.health()))),
                text("preview.info.food", "value", String.valueOf(snapshot.food())),
                text("preview.info.level", "value", String.valueOf(snapshot.level())),
                text("preview.info.gamemode", "value", snapshot.gameMode().name().toLowerCase(Locale.ROOT)),
                text("preview.info.effects", "value", String.valueOf(snapshot.effects().size())),
                text("preview.info.items", "value", String.valueOf(snapshot.itemCount())))));
        if (snapshot.enderChest() != null) {
            inventory.setItem(ENDER, Items.item(Material.ENDER_CHEST, text("preview.ender"), List.of()));
        }
        inventory.setItem(BACK, Items.item(Material.ARROW, text("preview.back"), List.of()));
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot == BACK) {
            Sounds.click(player);
            gui.open(player, targetName);
        } else if (slot == ENDER && snapshot.enderChest() != null) {
            Sounds.page(player);
            player.openInventory(new PreviewMenu(gui, viewer, targetName, entry, snapshot, !ender).getInventory());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private static ItemStack item(ItemStack[] items, int index) {
        return index < items.length && items[index] != null && !items[index].isEmpty() ? items[index].clone() : null;
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
