package fr.eternom.eterSync.module.sync;

import fr.eternom.eterSync.api.SyncApi;
import fr.eternom.eterSync.module.history.HistoryGui;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** L'API d'EterSync (SyncApi) : le plugin lui-même, vu de l'extérieur. */
public class SyncApiService implements SyncApi {

    private final SyncService sync;
    private final HistoryGui history;

    public SyncApiService(SyncService sync, HistoryGui history) {
        this.sync = sync;
        this.history = history;
    }

    @Override
    public boolean isLoading(UUID player) {
        return sync.isLoading(player);
    }

    @Override
    public Optional<ItemStack[]> lastInventory(UUID player) {
        return sync.getRepository().latest(player, sync.getGroup()).map(snapshot -> Arrays.stream(snapshot.inventory())
                .map(item -> item == null ? null : item.clone()).toArray(ItemStack[]::new));
    }

    @Override
    public void openHistory(Player viewer, String targetName) {
        history.open(viewer, targetName);
    }
}
