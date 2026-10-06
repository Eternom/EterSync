package fr.eternom.eterSync.module.history;

import fr.eternom.eterLib.helper.message.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /etersync history <joueur> : historique des sauvegardes et aperçu, en lecture seule (admin). */
public class HistoryCommand implements TabExecutor {

    private final HistoryGui gui;
    private final Messages messages;

    public HistoryCommand(HistoryGui gui, Messages messages) {
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "command.players-only");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("history")) {
            messages.send(player, "history.usage");
            return true;
        }
        gui.open(player, args[1]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return "history".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("history") : List.of();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
