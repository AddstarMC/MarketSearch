package au.com.addstar.marketsearch;

import au.com.addstar.marketsearch.plotproviders.PlotProvider;
import au.com.addstar.marketsearch.plotproviders.PlotSquaredPlotProvider;
import au.com.addstar.marketsearch.pricereduction.Database;
import au.com.addstar.marketsearch.pricereduction.PriceActivityListener;
import au.com.addstar.marketsearch.pricereduction.PriceReductionConfig;
import au.com.addstar.marketsearch.pricereduction.PriceReductionManager;
import au.com.addstar.marketsearch.pricereduction.PriceReductionScheduler;
import au.com.addstar.marketsearch.SlimefunNameDB.sfDBItem;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopChunk;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.common.util.RomanNumber;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class MarketSearch extends JavaPlugin {
    private static MarketSearch instance;
    private static final Logger logger = Logger.getLogger("Minecraft");
    private final EnchantAliasRegistry enchantRegistry = new EnchantAliasRegistry(this);
    private boolean debugEnabled = false;
    private FileConfiguration config;
    // Higher numbers lead to more debug messages
    private int debugLevel = 1;
    /** QuickShop's sentinel for an unlimited shop's stock/space, rather than a real count. */
    static final int UNLIMITED_STOCK = -1;

    /** Bounds each asynchronous chunk load so one slow chunk cannot stall a whole search. */
    private static final long CHUNK_LOAD_TIMEOUT_SECONDS = 10;

    /**
     * Marks a stock/space figure we could not determine, as opposed to a real count of zero. Shops
     * carrying this are still shown rather than filtered out, so a shop whose chest could not be
     * read is surfaced for investigation instead of silently vanishing from results.
     */
    private static final int UNKNOWN_COUNT = -2;

    private String marketWorld = null;
    private ShopManager quickShopManager = null;
    private PlotProvider plotProvider;
    private PluginDescriptionFile pdfFile = null;
    private Plugin sfPlugin = null;
    private NamespacedKey sfNSItemKey = null;
    public SlimefunNameDB sfNameDB = new SlimefunNameDB();
    public boolean sfEnabled = false;
    private SearchManager searchManager;
    private Database priceDatabase;
    private PriceReductionManager priceReductionManager;
    private PriceReductionScheduler priceReductionScheduler;

    public static MarketSearch getInstance() {
        return instance;
    }

    boolean isDebugEnabled() {
        return debugEnabled;
    }

    void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    void setDebugEnabled(boolean debugEnabled, int debugLevel) {
        this.debugEnabled = debugEnabled;
        this.debugLevel = debugLevel;
    }

    static class ShopResult {
        String plotOwner;
        String shopOwner;
        String itemName;
        String type;
        Integer stock = 0;
        Integer space = 0;
        Double price = 0D;
        Boolean enchanted = false;
        Map<Enchantment, Integer> enchants = null;
        Boolean potion = false;
        String potionType = null;
        Location shopLocation = null;
    }

    @Override
    public void onEnable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("MarketSearch");
        if (plugin instanceof MarketSearch) {
            instance = (MarketSearch) plugin;
        } else {
            throw new IllegalStateException("MarketSearch plugin is not enabled or not found");
        }

        // Register necessary events
        pdfFile = this.getDescription();
        configure();
        PluginManager pm = this.getServer().getPluginManager();

        if (pm.getPlugin("QuickShop-Hikari") != null) {
            QuickShopAPI qsapi = QuickShopAPI.getInstance();
            if (qsapi != null) {
                quickShopManager = qsapi.getShopManager();
                log("PlotProvider: QuickShop-Hikari hooked");
            }
        }

        if (pm.getPlugin("PlotSquared") != null) {
            Plugin plotSquared = pm.getPlugin("PlotSquared");
            if (plotSquared != null && plotSquared.isEnabled()) {
                plotProvider = new PlotSquaredPlotProvider();
                log("PlotProvider: PlotSquared hooked");
            }
        }

        if (pm.getPlugin("Slimefun") != null) {
            sfPlugin = pm.getPlugin("Slimefun");
            if (sfPlugin != null && sfPlugin.isEnabled()) {
                sfNSItemKey = new NamespacedKey(sfPlugin, "slimefun_item");
                if (sfNSItemKey != null) {
                    sfEnabled = true;
                    log("Slimefun integration enabled");
                } else {
                    warn("Slimefun was detected but was unable to create a NamespacedKey. Slimefun integration disabled.");
                }
            }
        }

        marketWorld = (config != null) ? config.getString("world", "market") : "market";
        enchantRegistry.loadEnchants();
        if (sfEnabled) loadSlimefunNameDB();
        this.searchManager = new SearchManager(this);

        // Register the PlayerListener
        pm.registerEvents(new PlayerListener(), this);

        String commandText = "marketsearch";
        PluginCommand command = getCommand(commandText);
        if (command != null) {
            command.setExecutor(new CommandListener(this));
            command.setAliases(Collections.singletonList("ms"));
        } else {
            warn("MarketSearch command not found  - plugin will not execute commands -check yml");
        }

        setupPriceReduction(pm);

        log(pdfFile.getName() + " " + pdfFile.getVersion() + " has been enabled");
    }

    private void setupPriceReduction(PluginManager pm) {
        PriceReductionConfig prConfig = new PriceReductionConfig(config);

        // Player tracking is the base layer: if a database is configured we always try to connect,
        // create the schema, seed, and track logins. The price-reduction commands are available
        // whenever the DB is up; the 'auto-reduce' flag only controls the daily scheduled run.
        if (!prConfig.isDbConfigured()) {
            log("Player tracking: disabled (no 'database.host' set in config.yml).");
            return;
        }
        // HikariCP and the MySQL driver are expected on the Paper runtime classpath (provided).
        if (!isClassPresent("com.zaxxer.hikari.HikariDataSource")
              || !isClassPresent("com.mysql.cj.jdbc.Driver")) {
            warn("Player tracking: disabled - HikariCP / MySQL driver are not on the classpath. "
                  + "(These are bundled by Paper; check your server build.)");
            return;
        }

        try {
            log("Player tracking: connecting to MySQL at " + prConfig.getDbHost() + ":"
                  + prConfig.getDbPort() + "/" + prConfig.getDbName()
                  + " (table prefix '" + prConfig.getTablePrefix() + "')...");
            priceDatabase = new Database(prConfig, getLogger());
        } catch (Exception e) {
            // Connection / schema failure: single-line message, everything DB-related stays off.
            warn("Player tracking: disabled - could not connect to the database (" + e.getMessage()
                  + "). MarketSearch search is unaffected.");
            getLogger().log(java.util.logging.Level.FINE, "Player tracking DB init error", e);
            shutdownPriceReduction();
            return;
        }

        // DB is up: enable login tracking + the price-reduction manager (manual commands).
        priceReductionManager = new PriceReductionManager(this, priceDatabase, prConfig);
        pm.registerEvents(new PriceActivityListener(this, priceDatabase, prConfig), this);
        log("Player tracking: active. Price reduction available via /ms pricereduce (threshold="
              + prConfig.getOfflineThresholdDays() + "d, " + (prConfig.getPercent() * 100)
              + "%/day, floor=$" + prConfig.getMinPrice() + ", market world='" + marketWorld + "').");

        // Daily automated run only when QuickShop is present AND auto-reduce is on.
        if (prConfig.isAutoReduce()) {
            if (quickShopManager == null) {
                warn("Price reduction: auto-reduce is on but QuickShop-Hikari is not available; "
                      + "the scheduled run is disabled (manual commands still work).");
            } else {
                priceReductionScheduler = new PriceReductionScheduler(this, priceReductionManager, prConfig);
                priceReductionScheduler.start();
                log("Price reduction: automated daily run ENABLED at " + prConfig.getRunTime() + ".");
            }
        } else {
            log("Price reduction: automated daily run is OFF (set 'price-reduction.auto-reduce: true' "
                  + "to schedule it). Manual /ms pricereduce run|dryrun still work.");
        }
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void shutdownPriceReduction() {
        if (priceReductionScheduler != null) {
            priceReductionScheduler.stop();
            priceReductionScheduler = null;
        }
        if (priceDatabase != null) {
            priceDatabase.close();
            priceDatabase = null;
        }
        priceReductionManager = null;
    }

    private void configure() {
        File file = new File(getDataFolder(), "config.yml");
        try {
            if (file.exists()) {
                config = YamlConfiguration.loadConfiguration(file);
                updateConfig(file);
            } else {
                getDataFolder().mkdirs();
                config = getConfig();
                updateConfig(file);
            }
        } catch (IOException e) {
            logger.fine("Errors creating config file");
        }
    }

    private void loadSlimefunNameDB() {
        File file = new File(getDataFolder(), "slimefun.csv");

        try {
            if (!file.exists())
                sfNameDB.load(getResource("slimefun.csv"));
            else
                sfNameDB.load(file);
        } catch (IOException e) {
            getLogger().severe("Unable to load Slimefun item name database");
            e.printStackTrace();
        }
    }

    private void updateConfig(File file) throws IOException {
        config.addDefault("world", "market");

        // Price reduction feature defaults
        config.addDefault("price-reduction.auto-reduce", false);
        config.addDefault("price-reduction.run-time", "02:00");
        config.addDefault("price-reduction.offline-threshold-days", 60);
        config.addDefault("price-reduction.percent", 0.02);
        config.addDefault("price-reduction.min-drop", 0.01);
        config.addDefault("price-reduction.min-price", 1.0);
        config.addDefault("price-reduction.shops-per-tick", 20);
        config.addDefault("price-reduction.return-message",
              "&eSome of your shop prices were reduced due to your absence.");
        config.addDefault("price-reduction.return-message-delay-ticks", 30);
        config.addDefault("price-reduction.audit.database", true);
        config.addDefault("price-reduction.audit.log-file", true);

        // Database connection (used only when price-reduction is enabled)
        config.addDefault("database.host", "localhost");
        config.addDefault("database.port", 3306);
        config.addDefault("database.name", "marketsearch");
        config.addDefault("database.user", "marketsearch");
        config.addDefault("database.password", "");
        config.addDefault("database.table-prefix", "ms_");
        config.addDefault("database.pool-size", 4);
        config.addDefault("database.gesuit-seed.enabled", true);
        config.addDefault("database.gesuit-seed.source-table", "gesuit.players");

        config.options().copyDefaults(true);
        config.save(file);
    }

    @Override
    public void onDisable() {
        // Drop any chunk tickets a search was still holding, so nothing is pinned after shutdown.
        for (World world : Bukkit.getWorlds()) {
            world.removePluginChunkTickets(this);
        }
        shutdownPriceReduction();
    }

    public ShopManager getQuickShopManager() {
        return quickShopManager;
    }

    public String getMarketWorld() {
        return marketWorld;
    }

    public PriceReductionManager getPriceReductionManager() {
        return priceReductionManager;
    }

    public Database getPriceDatabase() {
        return priceDatabase;
    }

    public EnchantAliasRegistry getEnchantRegistry() {
        return enchantRegistry;
    }

    public SearchManager getSearchManager() {
        return searchManager;
    }

    public List<ShopResult> searchMarket(Player player, ItemStack searchItem, SearchType searchType) {
        List<ShopResult> results = new ArrayList<>();
        // QuickShop 6.3 returns an empty map (not null) for an unknown world, so a mismatched
        // world name now yields zero results silently instead of tripping the null branch below.
        Map<ShopChunk, Map<Location, Shop>> map = quickShopManager.getShops(marketWorld);
        if (map == null || map.isEmpty()) {
            warn("No shops found in market world '" + marketWorld + "'. QuickShop knows about these "
                  + "worlds: " + quickShopManager.getShops().keySet()
                  + " (total shops: " + quickShopManager.getAllShops().size() + ")");
        }

        int maxDetailedCount = 50;
        int wrongItemCount = 0;
        int noStockCount = 0;
        int noSpaceCount = 0;
        long totalTime = 0;
        long shopResultTime = 0;
        sfDBItem sfSearch = null;

        // Only do Slimefun searches if the Slimefun plugin is loaded
        if (sfEnabled) {
            sfSearch = getSlimefunItemType(searchItem);
            if (sfSearch != null)
                debug("Item Slimefun type: " + sfSearch.fullname + " (" + sfSearch.sfname + ")");
        }

        final SearchManager smgr = getSearchManager();
        Material itemType = searchItem.getType();

        long startmain = System.nanoTime();
        if (map != null) {
            int totalChunks = map.size();
            int chunkCount = 0;
            debug("Searching " + map.size() + " shops for item " + getItemDetails(searchItem));
            for (Entry<ShopChunk, Map<Location, Shop>> chunks : map.entrySet()) {
                chunkCount++;
                double chunkProgress = (chunkCount / (double) totalChunks);
                debug("Searching chunk " + chunkCount + " of " + totalChunks + " (" + (chunkProgress * 100) + "%)");
                if (smgr.isSearching(player)) {
                    smgr.updateProgress(player, chunkProgress);
                } else {
                    // Player is not searching, so we can skip this
                    debug("Player is not searching, skipping progress update");
                }

                // The chunk is loaded lazily, only once a shop in it actually matches the search
                // and so needs its stock read. Most chunks hold nothing relevant to any given
                // search, and loading those is by far the most expensive thing a search can do.
                // Loading per chunk rather than per shop also means a search never holds more than
                // one chunk, and never unloads one while another shop in it still has to be read.
                boolean loadedByUs = false;
                try {
                    for (Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
                        try {
                            boolean skipresult = false;
                            final Shop shop = inChunk.getValue();
                            ItemStack shopItem = shop.getItem();

                            if (shopItem.getType() != itemType) {
                                // Wrong item type - skipped
                                skipresult = true;
                            }
                            else if (sfSearch != null) {
                                // Searching for Slimefun item, check if this one matches
                                sfDBItem sfItem = getSlimefunItemType(shopItem);
                                if ((sfItem == null) || (sfItem.sfname != sfSearch.sfname)) {
                                    skipresult = true;
                                }
                            } else {
                                // Vanilla search and item is the right type
                                // Ensure this is not also a Slimefun item
                                if (sfEnabled && hasSlimefunMeta(shopItem))
                                    skipresult = true;
                            }

                            if (skipresult) {
                                if (debugEnabled) {
                                    wrongItemCount++;
                                    if (wrongItemCount <= maxDetailedCount && debugLevel > 1) {
                                        logger.info("No match to " + shopItem.getType().name() + " in shop at "
                                                + shop.bukkitLocation().getBlockX() + " " + shop.bukkitLocation().getBlockY()
                                                + " " + shop.bukkitLocation().getBlockZ());
                                        if (wrongItemCount == maxDetailedCount) {
                                            logger.info(" ... max wrong item count limit reached; no more "
                                                    + "items will be logged");
                                        }
                                    }
                                }
                                continue;
                            }

                            // This shop matched, so its stock is needed: make sure the chunk is
                            // resident. Done at most once per chunk, on first match.
                            if (!loadedByUs) {
                                loadedByUs = holdChunk(chunks.getKey());
                            }

                            long startshop = System.nanoTime();
                            ShopResult shopResult;
                            try {
                                shopResult = getFutureShopResult(shop);
                            } catch (InterruptedException | ExecutionException e) {
                                // Log the cause with a stack trace: ExecutionException itself carries a
                                // null message, which previously turned every per-shop failure into a
                                // blank log line and a silently empty search result.
                                Throwable cause = e.getCause() != null ? e.getCause() : e;
                                logger.log(java.util.logging.Level.WARNING,
                                      "Failed to read shop at " + shop.bukkitLocation(), cause);
                                if (e instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                                continue;
                            } finally {
                                shopResultTime += System.nanoTime() - startshop;
                            }

                            if (searchType == SearchType.SELLING && shopResult.stock == 0) {
                                // stock < 0 means unlimited or unknown, so it is not filtered here.
                                if (debugEnabled) {
                                    noStockCount++;
                                    if (noStockCount <= maxDetailedCount) {
                                        logger.info("Match found, but no stock in shop at "
                                              + shop.bukkitLocation().getBlockX() + " " + shop.bukkitLocation().getBlockY() + " "
                                              + shop.bukkitLocation().getBlockZ() + ", shop item " + getItemDetails(shopItem));

                                        if (noStockCount == maxDetailedCount) {
                                            logger.info(" ... max no stock count limit reached; no more items will "
                                                  + "be logged");
                                        }
                                    }
                                }
                                continue;
                            }
                            if (searchType == SearchType.BUYING && shopResult.space == 0) {
                                // space < 0 means unlimited or unknown, so it is not filtered here.
                                if (debugEnabled) {
                                    noSpaceCount++;
                                    if (noSpaceCount <= maxDetailedCount) {
                                        logger.info("Match found, but no space to buy item in shop at "
                                              + shop.bukkitLocation().getBlockX() + " " + shop.bukkitLocation().getBlockY() + " "
                                              + shop.bukkitLocation().getBlockZ() + ", shop item " + getItemDetails(shopItem));

                                        if (noSpaceCount == maxDetailedCount) {
                                            logger.info(" ... max no space count limit reached; no more items "
                                                  + "will be logged");
                                        }
                                    }
                                }
                                continue;
                            }
                            if (!searchType.matches(shop)) {
                                // Wrong shop type
                                continue;
                            }
                            if (debugEnabled) {
                                logger.info("Match found, in shop at " + shop.bukkitLocation().getBlockX() + " "
                                      + shop.bukkitLocation().getBlockY() + " " + shop.bukkitLocation().getBlockZ()
                                      + "; storing");
                            }
                            // Is this item enchanted?
                            if (!shopItem.getEnchantments().isEmpty()) {
                                shopResult.enchants = shopItem.getEnchantments();
                                shopResult.enchanted = true;
                            }

                            // Is this an enchanted book?
                            if (shopItem.getType() == Material.ENCHANTED_BOOK) {
                                EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) shopItem.getItemMeta();
                                if (bookMeta != null && bookMeta.hasStoredEnchants()) {
                                    // Store the enchantment(s)
                                    shopResult.enchanted = true;
                                    shopResult.enchants = bookMeta.getStoredEnchants();
                                } else {
                                    if (debugEnabled) {
                                        logger.info("No stored enchants on book");
                                    }
                                }
                            }

                            // Is this a potion?
                            if (shopItem.getType() == Material.POTION
                                  || shopItem.getType() == Material.SPLASH_POTION
                                  || shopItem.getType() == Material.LINGERING_POTION
                                  || shopItem.getType() == Material.TIPPED_ARROW) {

                                shopResult.potion = true;
                                if (shopItem.getItemMeta() instanceof PotionMeta potionMeta
                                      && potionMeta.getBasePotionType() != null) {
                                    shopResult.potionType = potionMeta.getBasePotionType().getKey().getKey();
                                }
                            }
                            addshopResult(results, shopResult);
                        } catch (Exception exception) {
                            // Log with a stack trace; getMessage() alone is null for many exceptions
                            // and silently hid per-shop failures from the search results.
                            logger.log(java.util.logging.Level.WARNING,
                                  "Error while searching a shop", exception);
                        }
                    }
                } finally {
                    if (loadedByUs) {
                        releaseChunk(chunks.getKey());
                    }
                }
            }
        } else {
            warn("Quickshop returned NO Shops");
        }
        totalTime += System.nanoTime() - startmain;

        if (debugEnabled) {
            if (results.size() == 0) {
                logger.info("No results for item " + itemType.name());
            } else {
                logger.info("Sorting " + results.size() + " results for item " + itemType.name());
            }
            debug("Total time searching: " + totalTime);
            debug("Time fetching shop results: " + shopResultTime);
        }
        // Order results here
        if (searchType == SearchType.SELLING) {
            results.sort(ShopResultSort.ByPrice);
        } else {
            results.sort(ShopResultSort.ByPriceDescending);
        }
        return results;
    }

    public List<ShopResult> getPlayerShops(String playername) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playername);
        List<ShopResult> results = new ArrayList<>();
        if (player == null || !player.hasPlayedBefore()) {
            warn("Player " + playername + " does not exist");
            return results;
        }
        debug("Getting shops for " + player);
        List<Shop> shops = quickShopManager.getAllShops(player.getUniqueId());
        if (shops != null) {
            debug("Searching " + shops.size() + "x player shops...");
            for (Shop shop : shops) {
                try {
                    ShopResult result = getFutureShopResult(shop);
                    addshopResult(results, result);
                } catch (InterruptedException | ExecutionException e) {
                    MarketSearch.logger.info(e.getMessage());
                }
            }
        } else {
            warn("Quickshop returned NO Shops");
        }
        return results;
    }

    private void addshopResult(List<ShopResult> results, ShopResult result) {
        String owner = null;
        if (plotProvider == null) {
            result.plotOwner = result.shopOwner;
        } else {
            owner = plotProvider.getPlotOwner(result.shopLocation);
        }
        if (owner != null) {
            result.plotOwner = owner;
        } else {
            warn("Unable to find plot! " + result.shopLocation.toString());
        }
        results.add(result);
    }

    public boolean hasSlimefunMeta(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        return container.has(sfNSItemKey, PersistentDataType.STRING);
    }

    public sfDBItem getSlimefunItemType(ItemStack item) {
        if (!hasSlimefunMeta(item)) {
            debug("Item is NOT Slimefun");
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            debug("Item meta missing while checking Slimefun type");
            return null;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        String sfname = container.get(sfNSItemKey, PersistentDataType.STRING);
        if (sfname == null) {
            debug("Slimefun key not present despite meta check");
            return null;
        }

        sfname = sfname.toUpperCase();
        debug("Item is a Slimefun \"" + sfname + "\"");
        return sfNameDB.getSFItem(sfname);
    }

    public ItemStack makeSlimefunItem(ItemStack item, SlimefunNameDB.sfDBItem sfdbitem) {
        if (sfdbitem != null) {
            ItemMeta meta = item.getItemMeta();
            Plugin sfplugin = getServer().getPluginManager().getPlugin("Slimefun");
            NamespacedKey key = new NamespacedKey(sfplugin, "slimefun_item");
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, sfdbitem.sfname);
            item.setItemMeta(meta);
        }
        return item;
    }

    public String getEnchantText(Map<Enchantment, Integer> enchants) {
        return getEnchantText(enchants, true);
    }

    /**
     * Return a string of enchants.
     *
     * @param enchants   Map
     * @param abbreviate use abbreviation.
     * @return String
     */
    public String getEnchantText(Map<Enchantment, Integer> enchants, boolean abbreviate) {
        List<String> enchantList = new ArrayList<>();
        for (Entry<Enchantment, Integer> e : enchants.entrySet()) {
            Enchantment enchant = e.getKey();
            Integer level = e.getValue();
            String abbr;
            if (abbreviate) {
                abbr = enchantRegistry.getMainAlias(enchant);
                if (abbr == null) {
                    abbr = "??";
                }
            } else {
                abbr = e.getKey().getKey().getKey();
            }
            String roman = RomanNumber.toRoman(level);
            enchantList.add(abbr + " " + roman);
        }
        return StringUtils.join(enchantList.toArray(), "/");
    }

    private String getItemDetails(ItemStack item) {
        Material itemType = item.getType();
        StringBuilder description = new StringBuilder(itemType.name());

        if (item.hasItemMeta()) {
            ItemMeta shopItemMeta = item.getItemMeta();
            if (shopItemMeta != null) {
                Map<Enchantment, Integer> enchants = shopItemMeta.getEnchants();
                description.append(getEnchantText(enchants, false));
            }
        }

        return description.toString();
    }

    private void log(String data) {
        logger.info(pdfFile.getName() + " " + data);
    }

    void warn(String data) {
        logger.warning(pdfFile.getName() + " " + data);
    }

    void debug(String data) {
        if ((debugEnabled) && (debugLevel > 0)) {
            logger.info(pdfFile.getName() + " " + data);
        }
    }

    /*
     * Check if the player has the specified permission
     */
    private boolean hasPermission(Player player, String perm) {
        // Real player
        return player.hasPermission(perm);
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GREEN + "Available MarketSearch commands:");
        if (!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.find"))) {
            sender.sendMessage(ChatColor.AQUA + "/ms find <item> :" + ChatColor.WHITE
                  + " Find items being sold in the market");
            sender.sendMessage(ChatColor.AQUA + "/ms sell <item> :" + ChatColor.WHITE
                  + " Find items being bought in the market");
            sender.sendMessage(ChatColor.AQUA + "/ms find/sell hand :" + ChatColor.WHITE
                  + " Search using the item type you are currently holding");
            sender.sendMessage(ChatColor.AQUA + "/ms find/sell handexact :" + ChatColor.WHITE
                  + " Search using the exact item (including all properties including durability) you are "
                  + "currently holding");
            sender.sendMessage(ChatColor.GREEN + " - filter weapon enchants using /ms find diamond_sword:fire");
            sender.sendMessage(ChatColor.GREEN
                  + " - find spawn eggs using /ms find cow_spawn_egg or chicken_spawn_egg");
        }

        if (!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.stock"))) {
            sender.sendMessage(ChatColor.AQUA + "/ms stock :" + ChatColor.WHITE
                  + " Get a summary of your stock levels");
            sender.sendMessage(ChatColor.AQUA + "/ms stock empty :" + ChatColor.WHITE
                  + " List your shops with NO stock");
            sender.sendMessage(ChatColor.AQUA + "/ms stock lowest :" + ChatColor.WHITE
                  + " List your shops with lowest stock");
        }
        if (!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.stock.others"))) {
            sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> :" + ChatColor.WHITE
                  + " Get another player's stock levels");
            sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> empty :" + ChatColor.WHITE
                  + " Other player's shops with NO stock");
            sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> lowest :" + ChatColor.WHITE
                  + " Other player's shops with lowest stock");
        }
        if (!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.pricereduce.admin"))) {
            sender.sendMessage(ChatColor.AQUA + "/ms pricereduce run|dryrun|status :" + ChatColor.WHITE
                  + " Manage the offline price reduction routine");
        }
        if (!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.debug"))) {
            sender.sendMessage(ChatColor.AQUA + "/ms debug :" + ChatColor.WHITE
                  + " Switch debugging on and off as a toggle");
            sender.sendMessage(ChatColor.AQUA + "/ms debug 1 :" + ChatColor.WHITE
                  + " When enabling debug, optionally use 1 for normal logging");
            sender.sendMessage(ChatColor.AQUA + "/ms debug 2 :" + ChatColor.WHITE + " Use 2 for detailed logging");
        }
    }

    public static String initialCaps(String itemName) {
        String[] parts = itemName.split("_");
        StringBuilder itemNameInitialCaps = new StringBuilder();

        for (String part : parts) {
            if (itemNameInitialCaps.length() > 0) {
                itemNameInitialCaps.append("_");
            }

            itemNameInitialCaps.append(part.substring(0, 1).toUpperCase());
            itemNameInitialCaps.append(part.substring(1).toLowerCase());
        }

        return itemNameInitialCaps.toString();
    }

    private ShopResult getFutureShopResult(Shop shop) throws ExecutionException, InterruptedException {
        // The caller has already loaded this shop's chunk, so only the chest read itself is left,
        // and QuickShop requires that on the main thread.
        FutureTask<ShopResult> task = new FutureTask<>(() -> {
            try {
                return storeResult(shop, getShopCounts(shop));
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        });
        Bukkit.getScheduler().runTask(this, task);
        return task.get();
    }

    /**
     * Makes a shop chunk resident and holds it there, without blocking the main thread. Must be
     * called from an async task: it waits on Paper's asynchronous chunk load.
     *
     * <p>A plugin chunk ticket is taken before loading. Without one, nothing references a chunk
     * loaded this way and the server is free to unload it again immediately - including in the gap
     * between the load completing on the main thread and this search's read task running there,
     * which made stock reads fail intermittently for shops nobody was standing near.
     *
     * @param shopChunk the chunk to load and hold.
     * @return true if a ticket was taken and must be released once the chunk's shops are read.
     */
    private boolean holdChunk(ShopChunk shopChunk) {
        World world = Bukkit.getWorld(shopChunk.getWorld());
        if (world == null) {
            return false;
        }
        int x = shopChunk.getX();
        int z = shopChunk.getZ();
        try {
            // The ticket both loads the chunk and keeps it loaded until released.
            world.addPluginChunkTicket(x, z, this);
            if (!world.isChunkLoaded(x, z)) {
                // false = do not generate: a shop always sits in already-generated terrain, and
                // this stops a search ever creating new chunks.
                world.getChunkAtAsync(x, z, false).get(CHUNK_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            world.removePluginChunkTicket(x, z, this);
            return false;
        } catch (Exception e) {
            debug("Could not load chunk " + x + "," + z + ": " + e);
            world.removePluginChunkTicket(x, z, this);
            return false;
        }
    }

    /**
     * Releases the ticket held on a chunk, once every shop in it has been read. The server is then
     * free to unload the chunk when it chooses, as it would any other.
     *
     * @param shopChunk the chunk to release.
     */
    private void releaseChunk(ShopChunk shopChunk) {
        World world = Bukkit.getWorld(shopChunk.getWorld());
        if (world == null) {
            return;
        }
        try {
            world.removePluginChunkTicket(shopChunk.getX(), shopChunk.getZ(), this);
        } catch (Throwable t) {
            debug("Failed to release chunk " + shopChunk.getX() + "," + shopChunk.getZ() + ": " + t);
        }
    }

    private ShopResult storeResult(Shop shop, int[] counts) {
        ShopResult result = new ShopResult();
        ItemStack foundItem = shop.getItem();
        result.shopOwner = shop.getOwner().getDisplay();
        result.type = foundItem.getType().name();
        result.itemName = initialCaps(foundItem.getType().name());
        result.stock = counts[0];
        result.space = counts[1];
        result.price = shop.getPrice();
        result.shopLocation = shop.bukkitLocation();
        return result;
    }

    /**
     * Returns {stock, space} for a shop, preferring QuickShop's database-backed inventory cache so
     * that shops in unloaded chunks report real numbers. Falls back to the live inventory read when
     * the cache has no entry for the shop yet, which is correct for loaded chunks.
     *
     * @param shop the shop to read.
     * @return a two-element array of remaining stock and remaining space.
     */
    /**
     * Returns {stock, space} for a shop, loading the shop's chunk if necessary so the chest is
     * readable. Must run on the main thread.
     *
     * <p>QuickShop 6.3 resolves a shop's container inventory through {@code World.isChunkLoaded()}
     * and silently reports 0 when the chunk is unloaded, so the chest has to be loaded to get a
     * true figure. Its database record is not a usable substitute: QuickShop fires
     * {@code ShopInventoryCalculateEvent} from two places, each supplying -1 for the figure it did
     * not compute, and the listener writes both columns unconditionally - so a space calculation
     * overwrites the stored stock with -1. Stock levels have to be accurate here, so read the chest.
     *
     * @param shop the shop to read.
     * @param loadedByUs collects chunks this call loaded, so they can be unloaded afterwards.
     * @return a two-element array of remaining stock and remaining space.
     */
    private int[] getShopCounts(Shop shop) {
        // An unlimited shop never runs out; QuickShop reports -1 for it rather than a count.
        if (shop.isUnlimited()) {
            return new int[] {UNLIMITED_STOCK, UNLIMITED_STOCK};
        }
        Location loc = shop.bukkitLocation();
        World world = loc.getWorld();
        if (world == null || !world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            // Never read a shop whose chunk is not loaded. QuickShop's getInventory() deletes a
            // shop it considers corrupt, and only its own "chunk not loaded" early return stands
            // between an unreadable chest and that deletion. Report the count as unknown instead.
            debug("Skipping stock read for shop at " + loc + ": chunk is not loaded");
            return new int[] {UNKNOWN_COUNT, UNKNOWN_COUNT};
        }
        // Check the shop's block ourselves before letting QuickShop resolve it. QuickShop's
        // getInventory() resolves the chest with Block.getState(false) - the live tile entity - and
        // treats "not an InventoryHolder" as a corrupt shop, deleting it. Immediately after a chunk
        // loads, a chest's tile entity may not be attached yet, which is indistinguishable from a
        // genuinely missing container. Testing it here first means a shop in that state is reported
        // as unknown rather than handed to QuickShop while it looks broken.
        org.bukkit.block.Block shopBlock = shop.getShopBlock();
        if (!(shopBlock.getState(false) instanceof org.bukkit.inventory.InventoryHolder)) {
            // No container at the shop's location. This is the state QuickShop's getInventory()
            // treats as a corrupt shop and deletes, so report it rather than reading further.
            debug("Shop at " + loc + " (owner " + shop.getOwner().getDisplay() + ") has no"
                  + " container: the block there is " + shopBlock.getType());
            return new int[] {UNKNOWN_COUNT, UNKNOWN_COUNT};
        }
        try {
            // QuickShop tracks its own loaded/unloaded state separately from the chunk, and only
            // populates a shop's inventory once it considers the shop loaded.
            if (!shop.isLoaded() && quickShopManager != null) {
                quickShopManager.loadShop(shop);
            }
            // Reads the chest directly. QuickShop's database record is not a usable substitute: it
            // fires ShopInventoryCalculateEvent from two places, each supplying -1 for the figure
            // it did not compute, and the listener writes both columns unconditionally - so a
            // space calculation overwrites the stored stock with -1.
            return new int[] {shop.getRemainingStock(), shop.getRemainingSpace()};
        } catch (Throwable t) {
            debug("Could not read stock for shop at " + shop.bukkitLocation() + ": " + t);
            return new int[] {UNKNOWN_COUNT, UNKNOWN_COUNT};
        }
    }


    public static class ShopResultSort {
        static final Comparator<ShopResult> ByPrice = (shop1, shop2) -> {
            if (shop1.price.equals(shop2.price)) {
                return shop2.stock.compareTo(shop1.stock);

            }
            return shop1.price.compareTo(shop2.price);

        };

        static final Comparator<ShopResult> ByPriceDescending = (shop1, shop2) -> {

            if (shop1.price.equals(shop2.price)) {
                return shop2.space.compareTo(shop1.space);

            }

            return shop2.price.compareTo(shop1.price);

        };

        static final Comparator<ShopResult> ByStock = (shop1, shop2) -> {
            if (Objects.equals(shop1.stock, shop2.stock)) {
                return 0;
            }
            if (shop1.stock > shop2.stock) {
                return 1;
            } else {
                return -1;
            }
        };
    }
}
