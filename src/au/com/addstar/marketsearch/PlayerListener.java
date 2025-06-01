package au.com.addstar.marketsearch;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener implements Listener {
    // Ensure that any active search is cancelled if the player quits
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        MarketSearch plugin = MarketSearch.getInstance();
        if (plugin == null) return;

        SearchManager searchManager = plugin.getSearchManager();
        if (searchManager.isSearching(event.getPlayer())) {
            searchManager.cancelSearch(event.getPlayer());
        }
    }
}
