package au.com.addstar.marketsearch;

import au.com.addstar.marketsearch.plotproviders.PlotProvider;
import au.com.addstar.marketsearch.plotproviders.PlotSquaredPlotProvider;
import au.com.addstar.marketsearch.plotproviders.USkyBlockProvider;
import au.com.addstar.marketsearch.SlimefunNameDB.sfDBItem;
import au.com.addstar.monolith.util.PotionUtil;
import org.apache.commons.lang.StringUtils;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.maxgamer.quickshop.api.QuickShopAPI;
import org.maxgamer.quickshop.api.shop.Shop;
import org.maxgamer.quickshop.api.shop.ShopChunk;
import org.maxgamer.quickshop.api.shop.ShopManager;
import org.maxgamer.quickshop.api.shop.ShopType;
import org.maxgamer.quickshop.util.RomanNumber;
import us.talabrek.ultimateskyblock.api.uSkyBlockAPI;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Logger;

public class MarketSearch extends JavaPlugin {

    private static final Logger logger = Logger.getLogger("Minecraft");
    private final Map<Enchantment, String> enchantMap = new HashMap<>();
    private boolean debugEnabled = false;
    private FileConfiguration config;
    // Higher numbers lead to more debug messages
    private int debugLevel = 1;
    private String marketWorld = null;
    private ShopManager quickShopManager = null;
    private PlotProvider plotProvider;
    private PluginDescriptionFile pdfFile = null;
    private final Plugin sfPlugin = getServer().getPluginManager().getPlugin("Slimefun");
    private final NamespacedKey sfNSItemKey = new NamespacedKey(sfPlugin, "slimefun_item");
    public SlimefunNameDB sfNameDB = new SlimefunNameDB();

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
        // Register necessary events
        pdfFile = this.getDescription();
        configure();
        PluginManager pm = this.getServer().getPluginManager();

        if (pm.getPlugin("QuickShop") != null) {
            QuickShopAPI qsapi = (QuickShopAPI)pm.getPlugin("QuickShop");
            if (qsapi != null) {
                quickShopManager = qsapi.getShopManager();
                log("PlotProvider: QuickShop hooked");
            }
        }

        if (pm.getPlugin("uSkyBlock") != null) {
            Plugin uskyBlock = pm.getPlugin("uSkyBlock");
            if (uskyBlock != null && uskyBlock.isEnabled()) {
                plotProvider = new USkyBlockProvider((uSkyBlockAPI) uskyBlock);
                log("PlotProvider: uSkyBlock hooked");
            }
        }

        if (pm.getPlugin("PlotSquared") != null) {
            Plugin plotSquared = pm.getPlugin("PlotSquared");
            if (plotSquared != null && plotSquared.isEnabled()) {
                plotProvider = new PlotSquaredPlotProvider();
                log("PlotProvider: PlotSquared hooked");
            }
        }

        marketWorld = (config != null) ? config.getString("world", "market") : "market";
        loadEnchants();
        loadSlimefunNameDB();
        String commandText = "marketsearch";
        PluginCommand command = getCommand(commandText);
        if (command != null) {
            command.setExecutor(new CommandListener(this));
            command.setAliases(Collections.singletonList("ms"));
        } else {
            warn("MarketSearch command not found  - plugin will not execute commands -check yml");
        }

        log(pdfFile.getName() + " " + pdfFile.getVersion() + " has been enabled");
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
        config.options().copyDefaults(true);
        config.save(file);
    }

    @Override
    public void onDisable() {
        // Nothing yet
    }

    public List<ShopResult> searchMarket(ItemStack searchItem, ShopType searchType) {
        List<ShopResult> results = new ArrayList<>();
        Map<ShopChunk, Map<Location, Shop>> map = quickShopManager.getShops(marketWorld);

        int maxDetailedCount = 50;
        int wrongItemCount = 0;
        int noStockCount = 0;
        int noSpaceCount = 0;
        long totalTime = 0;
        long shopResultTime = 0;
        sfDBItem sfSearch = getSlimefunItemType(searchItem);
        if (sfSearch != null)
            debug("Item Slimefun type: " + sfSearch.fullname + " (" + sfSearch.sfname + ")");

        Material itemType = searchItem.getType();

        long startmain = System.nanoTime();
        if (map != null) {
            debug("Searching " + map.size() + " shops for item " + getItemDetails(searchItem));
            for (Entry<ShopChunk, Map<Location, Shop>> chunks : map.entrySet()) {
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
                            if (hasSlimefunMeta(shopItem))
                                skipresult = true;
                        }

                        if (skipresult) {
                            if (debugEnabled) {
                                wrongItemCount++;
                                if (wrongItemCount <= maxDetailedCount && debugLevel > 1) {
                                    logger.info("No match to " + shopItem.getType().name() + " in shop at "
                                            + shop.getLocation().getBlockX() + " " + shop.getLocation().getBlockY()
                                            + " " + shop.getLocation().getBlockZ());
                                    if (wrongItemCount == maxDetailedCount) {
                                        logger.info(" ... max wrong item count limit reached; no more "
                                                + "items will be logged");
                                    }
                                }
                            }
                            continue;
                        }

                        long startshop = System.nanoTime();
                        ShopResult shopResult;
                        try {
                            shopResult = getFutureShopResult(shop);
                        } catch (InterruptedException | ExecutionException e) {
                            MarketSearch.logger.info(e.getMessage());
                            continue;
                        } finally {
                            shopResultTime += System.nanoTime() - startshop;
                        }

                        if (searchType == ShopType.SELLING && shopResult.stock == 0) {
                            if (debugEnabled) {
                                noStockCount++;
                                if (noStockCount <= maxDetailedCount) {
                                    logger.info("Match found, but no stock in shop at "
                                          + shop.getLocation().getBlockX() + " " + shop.getLocation().getBlockY() + " "
                                          + shop.getLocation().getBlockZ() + ", shop item " + getItemDetails(shopItem));

                                    if (noStockCount == maxDetailedCount) {
                                        logger.info(" ... max no stock count limit reached; no more items will "
                                              + "be logged");
                                    }
                                }
                            }
                            continue;
                        }
                        if (searchType == ShopType.BUYING && shopResult.space == 0) {
                            // No space
                            if (debugEnabled) {
                                noSpaceCount++;
                                if (noSpaceCount <= maxDetailedCount) {
                                    logger.info("Match found, but no space to buy item in shop at "
                                          + shop.getLocation().getBlockX() + " " + shop.getLocation().getBlockY() + " "
                                          + shop.getLocation().getBlockZ() + ", shop item " + getItemDetails(shopItem));

                                    if (noSpaceCount == maxDetailedCount) {
                                        logger.info(" ... max no space count limit reached; no more items "
                                              + "will be logged");
                                    }
                                }
                            }
                            continue;
                        }
                        if (shop.getShopType() != searchType) {
                            // Wrong shop type
                            continue;
                        }
                        if (debugEnabled) {
                            logger.info("Match found, in shop at " + shop.getLocation().getBlockX() + " "
                                  + shop.getLocation().getBlockY() + " " + shop.getLocation().getBlockZ()
                                  + "; storing");
                        }
                        // Is this item enchanted?
                        if (shopItem.getEnchantments().size() > 0) {
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

                            PotionUtil potion = PotionUtil.fromItemStack(shopItem);
                            shopResult.potion = true;
                            shopResult.potionType = potion.toString();
                        }
                        addshopResult(results, shopResult);
                    } catch (Exception exception) {
                        MarketSearch.logger.info(exception.getMessage());
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
        if (searchType == ShopType.SELLING) {
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
        List<Shop> shops = quickShopManager.getPlayerAllShops(player.getUniqueId());
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
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        if (container.has(sfNSItemKey, PersistentDataType.STRING)) {
            return true;
        } else {
            return false;
        }
    }

    public sfDBItem getSlimefunItemType(ItemStack item) {
        if (hasSlimefunMeta(item)) {
            PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
            String sfname = container.get(sfNSItemKey, PersistentDataType.STRING).toUpperCase();
            debug("Item is a Slimefun \"" + sfname + "\"");
            return sfNameDB.getSFItem(sfname);
        } else {
            debug("Item is NOT Slimefun");
            return null;
        }
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
                abbr = enchantMap.get(enchant);
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

    private void loadEnchants() {
        enchantMap.clear();
        enchantMap.put(Enchantment.ARROW_DAMAGE, "power");
        enchantMap.put(Enchantment.ARROW_FIRE, "flame");
        enchantMap.put(Enchantment.ARROW_INFINITE, "inf");
        enchantMap.put(Enchantment.ARROW_KNOCKBACK, "punch");
        enchantMap.put(Enchantment.BINDING_CURSE, "binding");
        enchantMap.put(Enchantment.CHANNELING, "channel");
        enchantMap.put(Enchantment.DAMAGE_ALL, "dmg");        // Sharpness
        enchantMap.put(Enchantment.DAMAGE_ARTHROPODS, "bane");
        enchantMap.put(Enchantment.DAMAGE_UNDEAD, "smite");
        enchantMap.put(Enchantment.DEPTH_STRIDER, "strider");
        enchantMap.put(Enchantment.DIG_SPEED, "eff");
        enchantMap.put(Enchantment.DURABILITY, "dura");
        enchantMap.put(Enchantment.FIRE_ASPECT, "fire");
        enchantMap.put(Enchantment.FROST_WALKER, "frost");
        enchantMap.put(Enchantment.IMPALING, "impale");
        enchantMap.put(Enchantment.KNOCKBACK, "knock");
        enchantMap.put(Enchantment.LOOT_BONUS_BLOCKS, "fort");
        enchantMap.put(Enchantment.LOOT_BONUS_MOBS, "loot");
        enchantMap.put(Enchantment.LOYALTY, "loyal");
        enchantMap.put(Enchantment.LUCK, "luck");
        enchantMap.put(Enchantment.LURE, "lure");
        enchantMap.put(Enchantment.MENDING, "mend");
        enchantMap.put(Enchantment.MULTISHOT, "multi");
        enchantMap.put(Enchantment.OXYGEN, "air");
        enchantMap.put(Enchantment.PIERCING, "pierce");
        enchantMap.put(Enchantment.PROTECTION_ENVIRONMENTAL, "prot");
        enchantMap.put(Enchantment.PROTECTION_EXPLOSIONS, "blast");
        enchantMap.put(Enchantment.PROTECTION_FALL, "fall");
        enchantMap.put(Enchantment.PROTECTION_FIRE, "fireprot");
        enchantMap.put(Enchantment.PROTECTION_PROJECTILE, "proj");
        enchantMap.put(Enchantment.QUICK_CHARGE, "charge");
        enchantMap.put(Enchantment.RIPTIDE, "rip");
        enchantMap.put(Enchantment.SILK_TOUCH, "silk");
        enchantMap.put(Enchantment.SWEEPING_EDGE, "sweep");
        enchantMap.put(Enchantment.THORNS, "thorn");
        enchantMap.put(Enchantment.VANISHING_CURSE, "vanish");
        enchantMap.put(Enchantment.WATER_WORKER, "aqua");
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
        FutureTask<ShopResult> task = new FutureTask<>(() -> {
            try {
                return storeResult(shop);
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        });
        Bukkit.getScheduler().runTask(this, task);
        return task.get();
    }

    private ShopResult storeResult(Shop shop) {
        ShopResult result = new ShopResult();
        ItemStack foundItem = shop.getItem();
        result.shopOwner = shop.ownerName();    // name
        result.type = foundItem.getType().name();
        result.itemName = initialCaps(foundItem.getType().name());
        result.stock = shop.getRemainingStock();
        result.space = shop.getRemainingSpace();
        result.price = shop.getPrice();
        result.shopLocation = shop.getLocation();
        return result;
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
