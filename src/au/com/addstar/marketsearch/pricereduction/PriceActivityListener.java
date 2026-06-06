package au.com.addstar.marketsearch.pricereduction;

import au.com.addstar.marketsearch.MarketSearch;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Tracks player logins for offline-age calculation and, on return, stops further reductions and
 * informs the player (generic, non-itemised message) that their prices were reduced while away.
 */
public class PriceActivityListener implements Listener {

    private final MarketSearch plugin;
    private final Database database;
    private final PriceReductionConfig config;

    public PriceActivityListener(MarketSearch plugin, Database database, PriceReductionConfig config) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();
        final long now = System.currentTimeMillis();

        // Check for pending reductions first (since this login is what "resets" them), then record
        // the new login time and reset the counters.
        database.countReductionsSinceLogin(uuid).whenComplete((count, err) -> {
            if (err != null) {
                plugin.getLogger().log(Level.FINE, "Price reduction: failed to read reductions on join", err);
            } else if (count != null && count > 0) {
                notifyReturning(player);
                database.resetOwner(uuid, now).exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING, "Price reduction: failed to reset owner on join", ex);
                    return null;
                });
            }
            // Always update last login (this both records activity and resets the offline countdown).
            database.recordLogin(uuid, now).exceptionally(ex -> {
                plugin.getLogger().log(Level.WARNING, "Price reduction: failed to record login", ex);
                return null;
            });
        });
    }

    private void notifyReturning(Player player) {
        String message = ChatColor.translateAlternateColorCodes('&', config.getReturnMessage());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        }, Math.max(1, config.getReturnMessageDelayTicks()));
    }
}
