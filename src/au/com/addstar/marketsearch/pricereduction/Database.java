package au.com.addstar.marketsearch.pricereduction;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the HikariCP connection pool and all SQL for the price reduction feature. All public query
 * methods run off the main thread (via a dedicated single-thread executor) and return
 * {@link CompletableFuture}s, so the caller must marshal back to the main thread before touching
 * Bukkit/QuickShop state.
 */
public class Database implements AutoCloseable {

    private final Logger logger;
    private final String prefix;
    private final String dbName;
    private final boolean gesuitSeedEnabled;
    private final String gesuitSourceTable;
    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public Database(PriceReductionConfig config, Logger logger) {
        this.logger = logger;
        this.prefix = config.getTablePrefix();
        this.dbName = config.getDbName();
        this.gesuitSeedEnabled = config.isGesuitSeedEnabled();
        this.gesuitSourceTable = config.getGesuitSourceTable();

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("MarketSearch-Hikari");
        // Driver is provided by the Paper runtime (com.mysql:mysql-connector-j); HikariCP infers the
        // driver class from the jdbc:mysql: URL, so we don't set driverClassName explicitly.
        hc.setJdbcUrl("jdbc:mysql://" + config.getDbHost() + ":" + config.getDbPort() + "/"
                + config.getDbName() + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        hc.setUsername(config.getDbUser());
        hc.setPassword(config.getDbPassword());
        hc.setMaximumPoolSize(config.getPoolSize());
        hc.setConnectionTimeout(10_000);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");

        this.dataSource = new HikariDataSource(hc);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MarketSearch-DB");
            t.setDaemon(true);
            return t;
        });

        createTables();
    }

    private String t(String name) {
        return prefix + name;
    }

    private void createTables() {
        String activity = "CREATE TABLE IF NOT EXISTS " + t("player_activity") + " ("
                + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                + "last_login BIGINT NOT NULL,"
                + "last_reset BIGINT NULL"
                + ")";
        String reduction = "CREATE TABLE IF NOT EXISTS " + t("shop_reduction") + " ("
                + "shop_key VARCHAR(160) NOT NULL PRIMARY KEY,"
                + "owner_uuid CHAR(36) NOT NULL,"
                + "last_processed DATE NULL,"
                + "reductions_since_login INT NOT NULL DEFAULT 0,"
                + "last_price DOUBLE NOT NULL DEFAULT 0,"
                + "new_price DOUBLE NOT NULL DEFAULT 0,"
                + "last_reduction DOUBLE NOT NULL DEFAULT 0,"
                + "world VARCHAR(64) NULL,"
                + "x INT NOT NULL DEFAULT 0,"
                + "y INT NOT NULL DEFAULT 0,"
                + "z INT NOT NULL DEFAULT 0,"
                + "item VARCHAR(128) NULL,"
                + "updated_at BIGINT NOT NULL DEFAULT 0,"
                + "INDEX idx_owner (owner_uuid)"
                + ")";
        String audit = "CREATE TABLE IF NOT EXISTS " + t("price_audit") + " ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "ts BIGINT NOT NULL,"
                + "owner_uuid CHAR(36) NOT NULL,"
                + "shop_key VARCHAR(160) NOT NULL,"
                + "world VARCHAR(64) NULL,"
                + "x INT NOT NULL DEFAULT 0,"
                + "y INT NOT NULL DEFAULT 0,"
                + "z INT NOT NULL DEFAULT 0,"
                + "item VARCHAR(128) NULL,"
                + "old_price DOUBLE NOT NULL,"
                + "new_price DOUBLE NOT NULL,"
                + "dry_run TINYINT NOT NULL DEFAULT 0,"
                + "INDEX idx_owner (owner_uuid)"
                + ")";
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            boolean activityExisted = tableExists(c, prefix + "player_activity");
            st.executeUpdate(activity);
            st.executeUpdate(reduction);
            st.executeUpdate(audit);
            logger.info("Price reduction: tables ready (" + prefix + "player_activity, "
                    + prefix + "shop_reduction, " + prefix + "price_audit).");

            // Seed once, only when the activity table is freshly created.
            if (activityExisted) {
                logger.info("Price reduction: " + prefix + "player_activity already existed; "
                        + "skipping geSuit seed.");
            } else if (!gesuitSeedEnabled) {
                logger.info("Price reduction: " + prefix + "player_activity created; geSuit seed is "
                        + "disabled in config, so it starts empty (players fill in as they log in).");
            } else {
                logger.info("Price reduction: " + prefix + "player_activity newly created; seeding "
                        + "from geSuit (" + gesuitSourceTable + ")...");
                seedFromGesuit(c);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create MarketSearch price-reduction tables", e);
        }
    }

    private boolean tableExists(Connection c, String tableName) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(dbName, null, tableName, new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * One-off seed of {@code player_activity} from the geSuit players table (same MySQL server,
     * different database). geSuit stores unhyphenated 32-char UUIDs and a DATETIME lastonline; this
     * converts both to our formats in SQL. Uses INSERT IGNORE so existing (MS-tracked) rows are
     * never overwritten. Failures are logged and swallowed so the feature still works without a seed.
     */
    private void seedFromGesuit(Connection c) {
        // Validate the configured source table to avoid SQL injection via config (it is interpolated
        // directly, since a table identifier cannot be a bound parameter).
        if (!gesuitSourceTable.matches("[A-Za-z0-9_.]+")) {
            logger.warning("geSuit seed skipped: invalid source-table '" + gesuitSourceTable + "'");
            return;
        }
        String sql = "INSERT IGNORE INTO " + t("player_activity") + " (uuid, last_login) "
                + "SELECT LOWER(CONCAT("
                + "SUBSTRING(uuid,1,8),'-',SUBSTRING(uuid,9,4),'-',SUBSTRING(uuid,13,4),'-',"
                + "SUBSTRING(uuid,17,4),'-',SUBSTRING(uuid,21,12))), "
                + "UNIX_TIMESTAMP(lastonline) * 1000 "
                + "FROM " + gesuitSourceTable + " "
                + "WHERE uuid IS NOT NULL AND CHAR_LENGTH(uuid) = 32 AND lastonline IS NOT NULL";
        try (Statement st = c.createStatement()) {
            int rows = st.executeUpdate(sql);
            logger.info("Price reduction: seeded " + rows + " player activity rows from " + gesuitSourceTable);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "geSuit seed skipped: could not read " + gesuitSourceTable
                    + " (check the table exists and the DB user has SELECT on it)", e);
        }
    }

    private <T> CompletableFuture<T> async(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = dataSource.getConnection()) {
                return supplier.get(c);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }

    /** Records (or updates) a player's most recent login time. */
    public CompletableFuture<Void> recordLogin(UUID uuid, long when) {
        return async(c -> {
            String sql = "INSERT INTO " + t("player_activity") + " (uuid, last_login) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE last_login = VALUES(last_login)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, when);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Max UUIDs per IN(...) batch when checking eligibility, to keep statements small. */
    private static final int ELIGIBILITY_BATCH = 500;

    /**
     * Given a set of candidate owners (derived from the shops that actually exist in the market
     * world), returns the subset whose last_login is older than (now - thresholdMillis), i.e. those
     * eligible for reduction. The candidates are queried in batches of {@link #ELIGIBILITY_BATCH}
     * with an IN(...) clause so the statement never grows unbounded even with thousands of owners.
     * Runs on the DB executor (off the main thread).
     *
     * <p>Owners with no activity row are treated as ineligible (unknown -> never reduced).
     */
    public CompletableFuture<Set<UUID>> getEligibleAmong(Collection<UUID> candidates,
                                                         long thresholdMillis, long now) {
        return async(c -> {
            Set<UUID> eligible = new HashSet<>();
            if (candidates.isEmpty()) {
                return eligible;
            }
            long cutoff = now - thresholdMillis;
            List<UUID> all = new ArrayList<>(candidates);
            for (int start = 0; start < all.size(); start += ELIGIBILITY_BATCH) {
                List<UUID> batch = all.subList(start, Math.min(start + ELIGIBILITY_BATCH, all.size()));
                String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                String sql = "SELECT uuid FROM " + t("player_activity")
                        + " WHERE last_login < ? AND uuid IN (" + placeholders + ")";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, cutoff);
                    int i = 2;
                    for (UUID u : batch) {
                        ps.setString(i++, u.toString());
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                eligible.add(UUID.fromString(rs.getString("uuid")));
                            } catch (IllegalArgumentException ignored) {
                                // skip malformed uuid rows
                            }
                        }
                    }
                }
            }
            return eligible;
        });
    }

    public CompletableFuture<PlayerActivity> getActivity(UUID uuid) {
        return async(c -> {
            String sql = "SELECT last_login, last_reset FROM " + t("player_activity") + " WHERE uuid = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long login = rs.getLong("last_login");
                        long reset = rs.getLong("last_reset");
                        Long resetVal = rs.wasNull() ? null : reset;
                        return new PlayerActivity(uuid, login, resetVal);
                    }
                }
            }
            return null;
        });
    }

    /**
     * Bulk-loads the per-shop state for a set of owners in a single query, keyed by shop_key. Used
     * to pre-load all guard data before the main-thread paced phase so we never hit the DB in the
     * per-shop loop.
     */
    public CompletableFuture<java.util.Map<String, ShopReductionState>> getShopStatesForOwners(
            java.util.Collection<UUID> owners) {
        return async(c -> {
            java.util.Map<String, ShopReductionState> out = new java.util.HashMap<>();
            if (owners.isEmpty()) {
                return out;
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(owners.size(), "?"));
            String sql = "SELECT shop_key, last_processed, reductions_since_login, last_price, new_price "
                    + "FROM " + t("shop_reduction") + " WHERE owner_uuid IN (" + placeholders + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int i = 1;
                for (UUID owner : owners) {
                    ps.setString(i++, owner.toString());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        java.sql.Date d = rs.getDate("last_processed");
                        LocalDate processed = (d != null) ? d.toLocalDate() : null;
                        String key = rs.getString("shop_key");
                        out.put(key, new ShopReductionState(key, processed,
                                rs.getInt("reductions_since_login"),
                                rs.getDouble("last_price"),
                                rs.getDouble("new_price")));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<ShopReductionState> getShopState(String shopKey) {
        return async(c -> {
            String sql = "SELECT last_processed, reductions_since_login, last_price, new_price "
                    + "FROM " + t("shop_reduction") + " WHERE shop_key = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, shopKey);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        java.sql.Date d = rs.getDate("last_processed");
                        LocalDate processed = (d != null) ? d.toLocalDate() : null;
                        return new ShopReductionState(shopKey, processed,
                                rs.getInt("reductions_since_login"),
                                rs.getDouble("last_price"),
                                rs.getDouble("new_price"));
                    }
                }
            }
            return null;
        });
    }

    /** @return the number of reductions recorded for this owner's shops since their last login. */
    public CompletableFuture<Integer> countReductionsSinceLogin(UUID owner) {
        return async(c -> {
            String sql = "SELECT COALESCE(SUM(reductions_since_login),0) AS total FROM "
                    + t("shop_reduction") + " WHERE owner_uuid = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("total") : 0;
                }
            }
        });
    }

    /** Resets an owner's reduction counters on return; records the reset time on the activity row. */
    public CompletableFuture<Void> resetOwner(UUID owner, long when) {
        return async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + t("shop_reduction") + " SET reductions_since_login = 0 WHERE owner_uuid = ?")) {
                ps.setString(1, owner.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + t("player_activity") + " SET last_reset = ? WHERE uuid = ?")) {
                ps.setLong(1, when);
                ps.setString(2, owner.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Upserts the full per-shop snapshot after a reduction has been applied. */
    public CompletableFuture<Void> upsertShopState(ShopReductionUpdate u) {
        return async(c -> {
            String sql = "INSERT INTO " + t("shop_reduction") + " "
                    + "(shop_key, owner_uuid, last_processed, reductions_since_login, last_price, "
                    + "new_price, last_reduction, world, x, y, z, item, updated_at) "
                    + "VALUES (?,?,?,1,?,?,?,?,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "owner_uuid=VALUES(owner_uuid), last_processed=VALUES(last_processed), "
                    + "reductions_since_login=reductions_since_login+1, last_price=VALUES(last_price), "
                    + "new_price=VALUES(new_price), last_reduction=VALUES(last_reduction), "
                    + "world=VALUES(world), x=VALUES(x), y=VALUES(y), z=VALUES(z), "
                    + "item=VALUES(item), updated_at=VALUES(updated_at)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, u.shopKey());
                ps.setString(2, u.ownerUuid().toString());
                ps.setDate(3, java.sql.Date.valueOf(u.processedDate()));
                ps.setDouble(4, u.oldPrice());
                ps.setDouble(5, u.newPrice());
                ps.setDouble(6, u.oldPrice() - u.newPrice());
                ps.setString(7, u.world());
                ps.setInt(8, u.x());
                ps.setInt(9, u.y());
                ps.setInt(10, u.z());
                ps.setString(11, u.item());
                ps.setLong(12, u.updatedAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> insertAudit(ShopReductionUpdate u, boolean dryRun) {
        return async(c -> {
            String sql = "INSERT INTO " + t("price_audit") + " "
                    + "(ts, owner_uuid, shop_key, world, x, y, z, item, old_price, new_price, dry_run) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, u.updatedAt());
                ps.setString(2, u.ownerUuid().toString());
                ps.setString(3, u.shopKey());
                ps.setString(4, u.world());
                ps.setInt(5, u.x());
                ps.setInt(6, u.y());
                ps.setInt(7, u.z());
                ps.setString(8, u.item());
                ps.setDouble(9, u.oldPrice());
                ps.setDouble(10, u.newPrice());
                ps.setInt(11, dryRun ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        if (dataSource != null && !dataSource.isClosed()) {
            try {
                dataSource.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error closing MarketSearch database pool", e);
            }
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get(Connection c) throws SQLException;
    }

    /** Snapshot of a player's activity row. */
    public record PlayerActivity(UUID uuid, long lastLogin, Long lastReset) {
    }

    /** Snapshot of a shop's current reduction state (subset used by the routine/status). */
    public record ShopReductionState(String shopKey, LocalDate lastProcessed, int reductionsSinceLogin,
                                     double lastPrice, double newPrice) {
    }

    /** Full payload for upserting a shop's state and writing an audit row. */
    public record ShopReductionUpdate(String shopKey, UUID ownerUuid, LocalDate processedDate,
                                      double oldPrice, double newPrice, String world,
                                      int x, int y, int z, String item, long updatedAt) {
    }
}
