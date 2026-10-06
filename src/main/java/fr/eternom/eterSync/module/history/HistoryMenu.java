package fr.eternom.eterSync.module.history;

import fr.eternom.eterLib.helper.gui.Items;
import fr.eternom.eterLib.helper.gui.Menu;
import fr.eternom.eterLib.helper.gui.Sounds;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterSync.module.sync.SnapshotRepository.Entry;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Liste des sauvegardes d'un joueur, de la plus récente à la plus ancienne. Clic gauche = aperçu, clic droit = restaurer. */
public class HistoryMenu implements Menu {

    private static final int SIZE = 54;

    private final HistoryGui gui;
    private final Messages messages;
    private final Player viewer;
    private final UUID target;
    private final String targetName;
    private final List<Entry> entries;
    private final Inventory inventory;

    HistoryMenu(HistoryGui gui, Player viewer, UUID target, String targetName, List<Entry> entries) {
        this.gui = gui;
        this.messages = gui.messages();
        this.viewer = viewer;
        this.target = target;
        this.targetName = targetName;
        this.entries = entries;
        this.inventory = Bukkit.createInventory(this, SIZE, messages.get(viewer, "history.title", "player", targetName));
        for (int i = 0; i < entries.size() && i < SIZE; i++) {
            inventory.setItem(i, entryItem(entries.get(i), i == 0));
        }
    }

    @Override
    public void onClick(Player player, int slot, ClickType click) {
        if (slot >= entries.size()) {
            return;
        }
        Entry entry = entries.get(slot);
        Sounds.click(player);
        if (click.isRightClick()) {
            gui.confirmRestore(player, target, targetName, entry);
        } else if (click.isLeftClick()) {
            gui.preview(player, target, targetName, entry);
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** latest : la sauvegarde qui fait foi, mise en avant (brillante). */
    private ItemStack entryItem(Entry entry, boolean latest) {
        List<Component> lore = List.of(
                text("history.entry.server", "server", entry.server()),
                text("history.entry.reason", "reason", messages.plain(viewer, "history.reason." + entry.reason().name().toLowerCase())),
                Component.empty(),
                text("history.entry.preview"),
                text("history.entry.restore"));
        return Items.item(latest ? Material.ENDER_CHEST : Material.CHEST,
                text(latest ? "history.entry.latest" : "history.entry.name", "date", gui.date(entry)), lore, latest);
    }

    private Component text(String key, String... placeholders) {
        return messages.get(viewer, key, placeholders);
    }
}
