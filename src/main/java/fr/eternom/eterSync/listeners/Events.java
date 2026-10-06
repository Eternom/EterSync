package fr.eternom.eterSync.listeners;

import fr.eternom.eterSync.Main;
import fr.eternom.eterSync.module.sync.SyncListener;

public class Events {

    public Events(Main main) {
        main.getServer().getPluginManager().registerEvents(new SyncListener(main.getSync()), main);
    }

}
