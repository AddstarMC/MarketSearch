package au.com.addstar.marketsearch;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player “market search” sessions, each with its own BossBar.
 *
 * Usage:
 *   1. call startSearch(player, customTitle);
 *      → immediately shows a BossBar with progress = 0.0 and the exact text you passed in.
 *   2. periodically call updateProgress(player, fraction) with fraction ∈ [0.0, 1.0].
 *      → updates the bar’s fill. Once fraction >= 1.0, the search auto‐finishes and the bar is removed.
 *   3. isSearching(player) tells you if a session is already in progress (so you can block duplicate commands).
 *   4. cancelSearch(player) forcibly removes the bar and frees their slot.
 */
public class SearchManager {

    private final Plugin plugin;

    /** Tracks any currently running search per‐player. */
    private final Map<UUID, PlayerSearchSession> activeSearches = new HashMap<>();

    public SearchManager(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Returns true if the given player already has an in-flight search.
     */
    public boolean isSearching(Player player) {
        if (player == null) {
            return false; // No search if player is null or offline
        }
        return activeSearches.containsKey(player.getUniqueId());
    }

    /**
     * Starts a new search session for this player, showing a 0.0–progress BossBar
     * with exactly the text you specify. If they’re already searching, does nothing.
     *
     * @param player      the player who is searching
     * @param barTitle    the exact text to display on their BossBar (you can put any string here)
     */
    public void startSearch(Player player, String barTitle) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        if (activeSearches.containsKey(uuid)) {
            // Optionally: player.sendMessage("§cYou already have a search running!");
            return;
        }

        PlayerSearchSession session = new PlayerSearchSession(player, barTitle);
        activeSearches.put(uuid, session);
        // The constructor already creates & shows a 0.0 BossBar to the player.
    }

    /**
     * Manually push a new progress fraction [0.0 … 1.0] for this player's search.
     * If fraction >= 1.0, the search auto-finishes (cleanup only).
     * If the player isn't in an active session, this does nothing.
     *
     * @param player    player whose progress you’re updating
     * @param fraction  a value between 0.0 and 1.0 (clamped internally)
     */
    public void updateProgress(Player player, double fraction) {
        if (isSearching(player)) {
            UUID uuid = player.getUniqueId();
            PlayerSearchSession session = activeSearches.get(uuid);
            if (session == null) return;
            session.updateProgress(fraction);
        }
    }

    /**
     * Explicitly finish a player's search (same as calling updateProgress(player, 1.0)).
     * If no session, does nothing.
     */
    public void finishSearch(Player player) {
        if (isSearching(player)) {
            UUID uuid = player.getUniqueId();
            PlayerSearchSession session = activeSearches.get(uuid);
            if (session == null) return;
            session.finish();
        }
    }

    /**
     * Immediately cancels the player's search: removes the BossBar & frees up their slot.
     * If no session is active, does nothing.
     */
    public void cancelSearch(Player player) {
        if (isSearching(player)) {
            UUID uuid = player.getUniqueId();
            PlayerSearchSession session = activeSearches.remove(uuid);
            if (session != null) {
                session.cancel();
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Inner class: represents one player's search session (owns its BossBar, etc.)
    // ────────────────────────────────────────────────────────────────────────────
    private class PlayerSearchSession {
        private final UUID playerId;
        private final Player player;
        private final BossBar bossBar;

        public PlayerSearchSession(Player player, String barTitle) {
            this.player = player;
            this.playerId = player.getUniqueId();

            // Create a BossBar with the exact text they passed in, starting at 0.0 progress:
            this.bossBar = Bukkit.createBossBar(barTitle, BarColor.BLUE, BarStyle.SEGMENTED_20);
            this.bossBar.addPlayer(player);
            this.bossBar.setProgress(0.0);
            this.bossBar.setVisible(true);
        }

        /**
         * Called by SearchManager.updateProgress(...) with a fraction from 0.0 to 1.0.
         * If fraction >= 1.0, automatically calls finish().
         */
        public void updateProgress(double fraction) {
            // If the player disconnected mid-search, clean up immediately:
            if (!player.isOnline()) {
                cleanup();
                return;
            }

            // Clamp to [0.0, 1.0] and update the bar:
            double clamped = Math.max(0.0, Math.min(1.0, fraction));
            bossBar.setProgress(clamped);

            // If we've reached or passed 100%, finish & remove the bar:
            if (clamped >= 1.0) {
                finish();
            }
        }

        /**
         * Called when the search completes (either via updateProgress(..., 1.0) or finishSearch()).
         * Here we simply remove the bar. If you want to send a “search is done” message,
         * you can hook in your own logic or send it here.
         */
        public void finish() {
            // Optional: send a message or fire an event. For now, just clean up.
            cleanup();
        }

        /**
         * Called if you explicitly cancel via cancelSearch(player).
         */
        public void cancel() {
            // Optional: send a cancellation message
            player.sendMessage("§cSearch cancelled.");
            cleanup();
        }

        /**
         * Frees the BossBar and removes this session from activeSearches.
         */
        private void cleanup() {
            bossBar.removePlayer(player);
            bossBar.setVisible(false);
            activeSearches.remove(playerId);
        }
    }
}
