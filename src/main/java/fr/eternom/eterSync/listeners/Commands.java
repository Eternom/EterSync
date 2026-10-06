package fr.eternom.eterSync.listeners;

import fr.eternom.eterSync.Main;
import fr.eternom.eterSync.module.history.HistoryCommand;
import org.bukkit.command.PluginCommand;

import java.util.Objects;

public class Commands {

    public Commands(Main main) {
        HistoryCommand history = new HistoryCommand(main.getHistory(), main.getMessages());
        PluginCommand command = Objects.requireNonNull(main.getCommand("etersync"), "Commande absente du plugin.yml : etersync");
        command.setExecutor(history);
        command.setTabCompleter(history);
    }

}
