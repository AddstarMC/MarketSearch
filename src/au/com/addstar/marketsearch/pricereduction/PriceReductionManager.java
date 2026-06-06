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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
        long now = System.currentTimeMillis();
        notify(notifier, (dryRun ? "[dry-run] " : "") + "Selecting eligible offline owners...");

        var ownersFuture = (singleOwner != null)
                ? eligibleSingle(singleOwner, now)
                : database.getEligibleOwners(config.getOfflineThresholdMillis(), now);

        ownersFuture.whenComplete((owners, err) -> {
            if (err != null) {
                running.set(false);
                plugin.getLogger().log(Level.WARNING, "Price reduction: failed to select owners", err);
                notify(notifier, "Price reduction failed to query eligible owners (see console).");
                return;
            }
            if (owners.isEmpty()) {
                running.set(false);
                notify(notifier, (dryRun ? "[dry-run] " : "") + "No eligible offline owners found.");
                return;
            }
            // Pre-load all per-shop state for these owners in one query so the main-thread paced
            // phase never touches the DB in its loop.
            database.getShopStatesForOwners(owners).whenComplete((states, stateErr) -> {
                if (stateErr != null) {
                    running.set(false);
                    plugin.getLogger().log(Level.WARNING, "Price reduction: failed to pre-load shop states", stateErr);
                    notify(notifier, "Price reduction failed to load shop state (see console).");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> beginPacedPhase(owners, states, dryRun, notifier, now));
            });
        });
    }

    private java.util.concurrent.CompletableFuture<List<UUID>> eligibleSingle(UUID owner, long now) {
        return database.getActivity(owner).thenApply(activity -> {
            List<UUID> list = new ArrayList<>();
            if (activity != null
                    && activity.lastLogin() < now - config.getOfflineThresholdMillis()) {
                list.add(owner);
            }
            return list;
        });
    }

    private void beginPacedPhase(List<UUID> owners, java.util.Map<String, Database.ShopReductionState> states,
                                 boolean dryRun, CommandSender notifier, long now) {
        ShopManager shopManager = plugin.getQuickShopManager();
        if (shopManager == null) {
            running.set(false);
            notify(notifier, "QuickShop is not available; aborting.");
            return;
        }
        String marketWorld = plugin.getMarketWorld();
        LocalDate today = LocalDate.now();

        // Expand owners -> their market-world SELLING shops on the main thread, into a work queue.
        Deque<Shop> queue = new ArrayDeque<>();
        for (UUID owner : owners) {
            List<Shop> shops = shopManager.getAllShops(owner);
            if (shops == null) {
                continue;
            }
            for (Shop shop : shops) {
                Location loc = shop.getLocation();
                if (loc.getWorld() != null && loc.getWorld().getName().equals(marketWorld)) {
                    queue.add(shop);
                }
            }
        }

        notify(notifier, (dryRun ? "[dry-run] " : "") + "Processing " + queue.size()
                + " market shops from " + owners.size() + " offline owner(s) at "
                + config.getShopsPerTick() + "/tick...");

        Stats stats = new Stats();
        new BukkitRunnable() {
            @Override
            public void run() {
                int budget = config.getShopsPerTick();
                while (budget-- > 0 && !queue.isEmpty()) {
                    processShop(queue.poll(), states, dryRun, marketWorld, today, now, stats);
                }
                if (queue.isEmpty()) {
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
        } catch (Exception e) {
            stats.errors++;
            plugin.getLogger().log(Level.WARNING, "Price reduction: error processing shop", e);
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
                "%sPrice reduction complete: scanned=%d reduced=%d skipped(type=%d, unloaded=%d, "
                        + "noStock=%d, alreadyToday=%d, atFloor=%d) errors=%d",
                dryRun ? "[dry-run] " : "", s.scanned, s.reduced, s.skippedType, s.skippedUnloaded,
                s.skippedNoStock, s.skippedAlready, s.skippedAtFloor, s.errors);
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
        int skippedType;
        int skippedUnloaded;
        int skippedNoStock;
        int skippedAlready;
        int skippedAtFloor;
        int errors;
    }
}
