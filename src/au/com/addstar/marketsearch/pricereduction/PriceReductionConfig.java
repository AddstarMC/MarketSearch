package au.com.addstar.marketsearch.pricereduction;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed view over the {@code price-reduction} and {@code database} sections of config.yml.
 */
public class PriceReductionConfig {
    private final boolean autoReduce;        // run the daily reduction automatically
    private final String runTime;            // HH:mm
    private final int offlineThresholdDays;
    private final double percent;            // e.g. 0.02
    private final double minDrop;            // absolute minimum drop per run
    private final double minPrice;           // global price floor
    private final int shopsPerTick;
    private final int chunkUnloadDelayTicks;
    private final String returnMessage;
    private final int returnMessageDelayTicks;
    private final boolean auditDatabase;
    private final boolean auditLogFile;

    // database
    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;
    private final String tablePrefix;
    private final int poolSize;
    private final boolean gesuitSeedEnabled;
    private final String gesuitSourceTable;

    public PriceReductionConfig(FileConfiguration config) {
        ConfigurationSection pr = section(config, "price-reduction");
        this.autoReduce = pr.getBoolean("auto-reduce", false);
        this.runTime = pr.getString("run-time", "02:00");
        this.offlineThresholdDays = pr.getInt("offline-threshold-days", 60);
        this.percent = pr.getDouble("percent", 0.02);
        this.minDrop = pr.getDouble("min-drop", 0.01);
        this.minPrice = pr.getDouble("min-price", 1.0);
        this.shopsPerTick = Math.max(1, pr.getInt("shops-per-tick", 20));
        this.chunkUnloadDelayTicks = Math.max(0, pr.getInt("chunk-unload-delay-ticks", 40));
        this.returnMessage = pr.getString("return-message",
                "&eSome of your shop prices were reduced due to your absence.");
        this.returnMessageDelayTicks = pr.getInt("return-message-delay-ticks", 30);

        ConfigurationSection audit = pr.getConfigurationSection("audit");
        this.auditDatabase = audit == null || audit.getBoolean("database", true);
        this.auditLogFile = audit == null || audit.getBoolean("log-file", true);

        ConfigurationSection db = section(config, "database");
        this.dbHost = db.getString("host", "localhost");
        this.dbPort = db.getInt("port", 3306);
        this.dbName = db.getString("name", "marketsearch");
        this.dbUser = db.getString("user", "marketsearch");
        this.dbPassword = db.getString("password", "");
        this.tablePrefix = db.getString("table-prefix", "ms_");
        this.poolSize = Math.max(1, db.getInt("pool-size", 4));

        ConfigurationSection seed = db.getConfigurationSection("gesuit-seed");
        this.gesuitSeedEnabled = seed == null || seed.getBoolean("enabled", true);
        this.gesuitSourceTable = (seed != null)
                ? seed.getString("source-table", "gesuit.players") : "gesuit.players";
    }

    private static ConfigurationSection section(FileConfiguration config, String path) {
        ConfigurationSection s = config.getConfigurationSection(path);
        return (s != null) ? s : config.createSection(path);
    }

    /** Whether the daily reduction should run automatically on the schedule. */
    public boolean isAutoReduce() {
        return autoReduce;
    }

    /** Whether a database host is configured (and so a connection should be attempted). */
    public boolean isDbConfigured() {
        return dbHost != null && !dbHost.isBlank();
    }

    public String getRunTime() {
        return runTime;
    }

    public int getOfflineThresholdDays() {
        return offlineThresholdDays;
    }

    public long getOfflineThresholdMillis() {
        return offlineThresholdDays * 24L * 60L * 60L * 1000L;
    }

    public double getPercent() {
        return percent;
    }

    public double getMinDrop() {
        return minDrop;
    }

    public double getMinPrice() {
        return minPrice;
    }

    public int getShopsPerTick() {
        return shopsPerTick;
    }

    /**
     * Delay, in ticks, between finishing a chunk's shops and unloading a chunk this run loaded.
     * Gives QuickShop's chunk-unload handling and the chunk save-to-disk room to settle rather than
     * forcing it inline with the price writes.
     */
    public int getChunkUnloadDelayTicks() {
        return chunkUnloadDelayTicks;
    }

    public String getReturnMessage() {
        return returnMessage;
    }

    public int getReturnMessageDelayTicks() {
        return returnMessageDelayTicks;
    }

    public boolean isAuditDatabase() {
        return auditDatabase;
    }

    public boolean isAuditLogFile() {
        return auditLogFile;
    }

    public String getDbHost() {
        return dbHost;
    }

    public int getDbPort() {
        return dbPort;
    }

    public String getDbName() {
        return dbName;
    }

    public String getDbUser() {
        return dbUser;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public boolean isGesuitSeedEnabled() {
        return gesuitSeedEnabled;
    }

    public String getGesuitSourceTable() {
        return gesuitSourceTable;
    }
}
