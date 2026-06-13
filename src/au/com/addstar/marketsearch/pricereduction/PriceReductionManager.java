package au.com.addstar.marketsearch.pricereduction;

import au.com.addstar.marketsearch.MarketSearch;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.api.shop.ShopType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Orchestrates a price reduction run. Eligibility is selected asynchronously from the database; the
 * actual QuickShop reads/writes happen on the main thread, paced by a configurable shops-per-tick
 * budget. Supports a dry-run mode which computes and records what would change without touching
 * shop prices.
 */
public class PriceReductionManager {

    private static final DateTimeFormatter LOG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MarketSearch plugin;
    private final Database database;
    private final PriceReductionConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PriceReductionManager(MarketSearch plugin, Database database, PriceReductionConfig config) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Starts a full run across all eligible offline owners.
     *
     * @param dryRun   if true, compute/record but never change prices
     * @param notifier optional sender to receive start/finish summaries (may be null)
     */
    public void startRun(boolean dryRun, CommandSender notifier) {
        startRunInternal(dryRun, notifier, null);
    }

    /**
     * Starts a run scoped to a single owner (used by {@code dryrun <player>} and targeted runs).
     */
    public void startRunForOwner(boolean dryRun, CommandSender notifier, UUID owner) {
        startRunInternal(dryRun, notifier, owner);
    }

    private void startRunInternal(boolean dryRun, CommandSender notifier, UUID singleOwner) {
        if (!running.compareAndSet(false, true)) {
            notify(notifier, "A price reduction run is already in progress.");
            return;
        }
        ShopManager shopManager = plugin.getQuickShopManager();
        if (shopManager == null) {
            running.set(false);
            notify(notifier, "QuickShop is not available; aborting.");
            return;
        }
        long now = System.currentTimeMillis();
        String marketWorld = plugin.getMarketWorld();
        Stats stats = new Stats();

        // Phase 1 (main thread): gather the market-world shops up front, driven by what shops
        // actually exist - NOT by walking the player table. This scales with the number of market
        // shops, not the (potentially huge) activity table. Collect distinct owners as we go.
        notify(notifier, (dryRun ? "[dry-run] " : "") + "Scanning market-world shops...");
        Deque<Shop> candidateShops = new ArrayDeque<>();
        Set<UUID> candidateOwners = new HashSet<>();
        collectMarketShops(shopManager, marketWorld, singleOwner, candidateShops, candidateOwners, stats, notifier);

        if (candidateShops.isEmpty()) {
            running.set(false);
            notify(notifier, (dryRun ? "[dry-run] " : "") + "No market shops found to consider.");
            return;
        }

        // Phase 2 (async): of the (small) set of shop owners, find which are offline past threshold,
        // then pre-load per-shop state for those owners - both off the main thread.
        notify(notifier, (dryRun ? "[dry-run] " : "") + "Checking " + candidateOwners.size()
                + " shop owner(s) for offline eligibility...");
        database.getEligibleAmong(candidateOwners, config.getOfflineThresholdMillis(), now)
                .thenCompose(eligibleOwners ->
                        database.getShopStatesForOwners(eligibleOwners)
                                .thenApply(states -> new Object[]{eligibleOwners, states}))
                .whenComplete((result, err) -> {
                    if (err != null) {
                        running.set(false);
                        plugin.getLogger().log(Level.WARNING, "Price reduction: eligibility/state query failed", err);
                        notify(notifier, "Price reduction failed during the database lookup (see console).");
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    Set<UUID> eligibleOwners = (Set<UUID>) result[0];
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Database.ShopReductionState> states =
                            (java.util.Map<String, Database.ShopReductionState>) result[1];
                    if (eligibleOwners.isEmpty()) {
                        running.set(false);
                        notify(notifier, (dryRun ? "[dry-run] " : "") + "No eligible offline owners among the "
                                + candidateOwners.size() + " shop owner(s).");
                        return;
                    }
                    // Phase 3 (main thread): pace through the in-hand shops whose owner is eligible.
                    Bukkit.getScheduler().runTask(plugin, () ->
                            beginPacedPhase(candidateShops, eligibleOwners, states, dryRun, notifier,
                                    marketWorld, now, stats));
                });
    }

    /**
     * Main-thread collection of market-world shops. For a full run this uses
     * {@link ShopManager#getShopsInWorld(String)} (work scales with market shops, not players); for a
     * single-owner run it uses {@code getAllShops(owner)} filtered to the market world. Per-shop
     * Throwable handling guards against an incompatible QuickShop build.
     */
    private void collectMarketShops(ShopManager shopManager, String marketWorld, UUID singleOwner,
                                    Deque<Shop> out, Set<UUID> owners, Stats stats, CommandSender notifier) {
        try {
            List<Shop> shops = (singleOwner != null)
                    ? shopManager.getAllShops(singleOwner)
                    : shopManager.getShopsInWorld(marketWorld);
            if (shops == null) {
                return;
            }
            for (Shop shop : shops) {
                try {
                    Location loc = shop.getLocation();
                    if (loc.getWorld() != null && loc.getWorld().getName().equals(marketWorld)) {
                        out.add(shop);
                        owners.add(shop.getOwner().getUniqueId());
                    }
                } catch (Throwable t) {
                    stats.errors++;
                    logShopApiError(t, stats);
                }
            }
        } catch (Throwable t) {
            stats.errors++;
            logShopApiError(t, stats);
        }
        if (stats.errors > 0) {
            notify(notifier, "Warning: " + stats.errors + " shop(s) could not be read - this usually "
                    + "means the installed QuickShop version is incompatible with this build of "
                    + "MarketSearch. See console.");
        }
    }

    private void beginPacedPhase(Deque<Shop> candidateShops, Set<UUID> eligibleOwners,
                                 java.util.Map<String, Database.ShopReductionState> states,
                                 boolean dryRun, CommandSender notifier, String marketWorld,
                                 long now, Stats stats) {
        LocalDate today = LocalDate.now();

        notify(notifier, (dryRun ? "[dry-run] " : "") + "Processing up to " + candidateShops.size()
                + " market shops (" + eligibleOwners.size() + " eligible owner(s)) at "
                + config.getShopsPerTick() + "/tick...");

        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = config.getShopsPerTick();
                while (budget-- > 0 && !candidateShops.isEmpty()) {
                    Shop shop = candidateShops.poll();
                    // Skip shops whose owner isn't eligible (cheap owner-UUID check, no DB).
                    try {
                        if (!eligibleOwners.contains(shop.getOwner().getUniqueId())) {
                            stats.skippedNotEligible++;
                            continue;
                        }
                    } catch (Throwable t) {
                        stats.errors++;
                        logShopApiError(t, stats);
                        continue;
                    }
                    processShop(shop, states, dryRun, marketWorld, today, now, stats);
                }
                if (candidateShops.isEmpty()) {
                    cancel();
                    finish(dryRun, notifier, stats);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void processShop(Shop shop, java.util.Map<String, Database.ShopReductionState> states,
                             boolean dryRun, String marketWorld, LocalDate today, long now, Stats stats) {
        try {
            stats.scanned++;
            Location loc = shop.getLocation();
            if (loc.getWorld() == null || !loc.getWorld().getName().equals(marketWorld)) {
                return; // belt-and-braces: never touch non-market-world shops
            }
            if (shop.getShopType() != ShopType.SELLING) {
                stats.skippedType++;
                return;
            }
            if (!shop.isLoaded()) {
                stats.skippedUnloaded++;
                return;
            }
            if (shop.getRemainingStock() == 0) {
                stats.skippedNoStock++;
                return;
            }

            String shopKey = PriceMath.shopKey(loc);

            // last_processed guard (one drop per calendar day) — read from the pre-loaded state map,
            // so this loop never touches the DB.
            Database.ShopReductionState state = states.get(shopKey);
            if (state != null && today.equals(state.lastProcessed())) {
                stats.skippedAlready++;
                return;
            }

            double price = shop.getPrice();
            if (price <= config.getMinPrice()) {
                stats.skippedAtFloor++;
                return;
            }
            double newPrice = PriceMath.computeNewPrice(price, config.getPercent(),
                    config.getMinDrop(), config.getMinPrice());
            if (newPrice >= price) {
                stats.skippedAtFloor++;
                return;
            }

            String item = shop.getItem().getType().name();
            Database.ShopReductionUpdate update = new Database.ShopReductionUpdate(
                    shopKey, shop.getOwner().getUniqueId(), today, price, newPrice,
                    loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                    item, now);

            audit(update, dryRun);

            if (!dryRun) {
                shop.setPrice(newPrice);
                shop.update();
                database.upsertShopState(update).exceptionally(ex -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Price reduction: failed to persist shop state for " + shopKey, ex);
                    return null;
                });
            }
            stats.reduced++;
        } catch (Throwable t) {
            // Throwable (not just Exception): an incompatible QuickShop build raises NoSuchMethodError
            // / LinkageError, which must not abort the whole paced run.
            stats.errors++;
            logShopApiError(t, stats);
        }
    }

    /**
     * Logs a shop-access failure. The first one is logged at WARNING with the full stack trace
     * (likely a QuickShop binary incompatibility); subsequent ones are summarised to avoid spamming
     * the console when every shop fails for the same reason.
     */
    private void logShopApiError(Throwable t, Stats stats) {
        if (stats.errors == 1) {
            plugin.getLogger().log(Level.WARNING,
                    "Price reduction: failed to read a shop via the QuickShop API. If this is a "
                    + "NoSuchMethodError/LinkageError, the installed QuickShop-Hikari version is "
                    + "incompatible with this build of MarketSearch. Further errors this run are "
                    + "counted but not stack-traced.", t);
        } else {
            plugin.getLogger().fine("Price reduction: shop read error #" + stats.errors
                    + " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }

    private void audit(Database.ShopReductionUpdate u, boolean dryRun) {
        if (config.isAuditDatabase()) {
            database.insertAudit(u, dryRun).exceptionally(ex -> {
                plugin.getLogger().log(Level.WARNING, "Price reduction: failed to write audit row", ex);
                return null;
            });
        }
        if (config.isAuditLogFile()) {
            writeAuditLine(u, dryRun);
        }
    }

    private void writeAuditLine(Database.ShopReductionUpdate u, boolean dryRun) {
        Path file = plugin.getDataFolder().toPath().resolve("price-reduction.log");
        String line = String.format("%s %s%s owner=%s shop=%s item=%s %.2f -> %.2f%n",
                LOG_TS.format(java.time.LocalDateTime.now()),
                dryRun ? "[DRY] " : "", "REDUCE",
                u.ownerUuid(), u.shopKey(), u.item(), u.oldPrice(), u.newPrice());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(line);
        } catch (IOException e) {
            plugin.getLogger().log(Level.FINE, "Price reduction: failed to write audit log line", e);
        }
    }

    private void finish(boolean dryRun, CommandSender notifier, Stats s) {
        running.set(false);
        String summary = String.format(
                "%sPrice reduction complete: scanned=%d reduced=%d skipped(notEligible=%d, type=%d, "
                        + "unloaded=%d, noStock=%d, alreadyToday=%d, atFloor=%d) errors=%d",
                dryRun ? "[dry-run] " : "", s.scanned, s.reduced, s.skippedNotEligible, s.skippedType,
                s.skippedUnloaded, s.skippedNoStock, s.skippedAlready, s.skippedAtFloor, s.errors);
        plugin.getLogger().info(summary);
        notify(notifier, summary);
    }

    private void notify(CommandSender sender, String message) {
        if (sender != null) {
            sender.sendMessage("[MarketSearch] " + message);
        }
    }

    private static final class Stats {
        int scanned;
        int reduced;
        int skippedNotEligible;
        int skippedType;
        int skippedUnloaded;
        int skippedNoStock;
        int skippedAlready;
        int skippedAtFloor;
        int errors;
    }
}
