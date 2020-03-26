package au.com.addstar.marketsearch;

import au.com.addstar.marketsearch.plotproviders.PlotProvider;
import au.com.addstar.marketsearch.plotproviders.PlotSquaredPlotProvider;
import au.com.addstar.marketsearch.plotproviders.USkyBlockProvider;
import au.com.addstar.monolith.util.PotionUtil;
import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.maxgamer.QuickShop.QuickShop;
import org.maxgamer.QuickShop.Shop.Shop;
import org.maxgamer.QuickShop.Shop.ShopChunk;
import org.maxgamer.QuickShop.Shop.ShopManager;
import org.maxgamer.QuickShop.Shop.ShopType;
import org.maxgamer.QuickShop.exceptions.InvalidShopException;
import us.talabrek.ultimateskyblock.api.uSkyBlockAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.logging.Logger;

public class MarketSearch extends JavaPlugin {

    private static final Logger logger = Logger.getLogger("Minecraft");
    private final Map<Enchantment, String> enchantMap = new HashMap<>();
    private boolean debugEnabled = false;
    // Higher numbers lead to more debug messages
    private int debugLevel = 1;
    private String marketWorld = null;
    private ShopManager quickShopManager = null;
    private PlotProvider plotProvider;
    private PluginDescriptionFile pdfFile = null;

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
        Integer stock;
        Integer space;
        Double price;
        Boolean enchanted = false;
        Map<Enchantment, Integer> enchants = null;
        Boolean potion = false;
        String potionType = null;
        Location shopLocation;
    }

    @Override
    public void onEnable() {
        // Register necessary events
        pdfFile = this.getDescription();
        PluginManager pm = this.getServer().getPluginManager();
        quickShopManager = QuickShop.instance.getShopManager();

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

        loadEnchants();

        marketWorld = "market";
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

    @Override
    public void onDisable() {
        // Nothing yet
    }

    public List<ShopResult> searchMarket(ItemStack searchItem, ShopType searchType) {
        List<ShopResult> results = new ArrayList<>();
        HashMap<ShopChunk, HashMap<Location, Shop>> map = quickShopManager.getShops(marketWorld);

        int maxDetailedCount = 50;
        int wrongItemCount = 0;
        int noStockCount = 0;
        int noSpaceCount = 0;

        Material itemType = searchItem.getType();

        if (map != null) {

            if (debugEnabled) {
                debug("Searching shops for item " + getItemDetails(searchItem));
            }

            for (Entry<ShopChunk, HashMap<Location, Shop>> chunks : map.entrySet()) {

                for (Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
                    try {
                        Shop shop = inChunk.getValue();
                        ItemStack shopItem = shop.getItem();

                        if (shopItem.getType() != itemType) {
                            // Wrong item
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
                        if (searchType == ShopType.SELLING && shop.getRemainingStock() == 0) {
                            // No stock
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
                        if (searchType == ShopType.BUYING && shop.getRemainingSpace() == 0) {
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

                        ShopResult result = storeResult(shop);

                        // Is this item enchanted?
                        if (shopItem.getEnchantments().size() > 0) {
                            result.enchants = shopItem.getEnchantments();
                            result.enchanted = true;
                        }

                        // Is this an enchanted book?
                        if (shopItem.getType() == Material.ENCHANTED_BOOK) {

                            EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) shopItem.getItemMeta();

                            if (bookMeta != null && bookMeta.hasStoredEnchants()) {
                                // Store the enchantment(s)
                                result.enchanted = true;
                                result.enchants = bookMeta.getStoredEnchants();
                            } else {
                                if (debugEnabled) {
                                    logger.info("No stored enchants on book");
                                }
                            }
                        }

                        // Is this a potion?
                        if (shopItem.getType() == Material.POTION
                              || shopItem.getType() == Material.SPLASH_POTION
                              || shopItem.getType() == Material.LINGERING_POTION) {

                            PotionUtil potion = PotionUtil.fromItemStack(shopItem);
                            result.potion = true;
                            result.potionType = potion.toString();
                        }

                        addshopResult(results, shop, result);

                        // Store the shop location so we can teleport the player later
                        result.shopLocation = shop.getLocation();
                    } catch (InvalidShopException exception) {
                        MarketSearch.logger.info(exception.getMessage());
                    }
                }
            }
        } else {
            warn("Quickshop returned NO Shops");
        }

        if (debugEnabled) {
            if (results.size() == 0) {
                logger.info("No results for item " + itemType.name());
            } else {
                logger.info("Sorting " + results.size() + " results for item " + itemType.name());
            }
        }

        // Order results here
        if (searchType == ShopType.SELLING) {
            results.sort(ShopResultSort.ByPrice);
        } else {
            results.sort(ShopResultSort.ByPriceDescending);
        }
        return results;
    }

    public List<ShopResult> getPlayerShops(String player) {

        List<ShopResult> results = new ArrayList<>();
        HashMap<ShopChunk, HashMap<Location, Shop>> map = quickShopManager.getShops(marketWorld);
        if (map != null) {
            HashMap<ShopChunk, HashMap<Location, Shop>> shops = quickShopManager.getShops(marketWorld);
            if (shops != null) {
                for (Entry<ShopChunk, HashMap<Location, Shop>> chunks : shops.entrySet()) {
                    if (chunks.getValue() != null) {
                        for (Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
                            Shop shop = inChunk.getValue();
                            if (shop.getOwner().getName() != null) {
                                if (shop.getOwner().getName().equalsIgnoreCase(player)) {
                                    try {
                                        ShopResult result = storeResult(shop);
                                        addshopResult(results, shop, result);
                                    } catch (InvalidShopException e) {
                                        MarketSearch.logger.info(e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                warn("MarketWorld returned NO Shops");
            }
        } else {
            warn("Quickshop returned NO Shops");
        }
        return results;
    }

    private void addshopResult(List<ShopResult> results, Shop shop, ShopResult result) {
        String owner = plotProvider.getPlotOwner(shop.getLocation());
        if (owner != null) {
            result.plotOwner = owner;
            results.add(result);
        } else {
            warn("Unable to find plot! " + shop.getLocation().toString());
        }
    }

    public String getEnchantText(Map<Enchantment, Integer> enchants) {
        List<String> elist = new ArrayList<>();
        for (Entry<Enchantment, Integer> e : enchants.entrySet()) {
            Enchantment enchant = e.getKey();
            Integer level = e.getValue();
            String abbr = enchantMap.get(enchant);
            if (abbr == null) {
                abbr = "??";
            }
            elist.add(abbr + level);
        }

        // Return sorted string list
        return StringUtils.join(elist.toArray(), "/");
    }

    private void loadEnchants() {
        enchantMap.clear();
        enchantMap.put(Enchantment.ARROW_DAMAGE, "pierce");
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
        enchantMap.put(Enchantment.OXYGEN, "air");
        enchantMap.put(Enchantment.PROTECTION_ENVIRONMENTAL, "prot");
        enchantMap.put(Enchantment.PROTECTION_EXPLOSIONS, "blast");
        enchantMap.put(Enchantment.PROTECTION_FALL, "fall");
        enchantMap.put(Enchantment.PROTECTION_FIRE, "fireprot");
        enchantMap.put(Enchantment.PROTECTION_PROJECTILE, "proj");
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

                for (final Entry<Enchantment, Integer> entries : enchants.entrySet()) {
                    description.append(", enchantment: ").append(entries.getKey().getKey()).append(" ")
                          .append(entries.getValue());
                }
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
        if (debugEnabled) {
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

    private ShopResult storeResult(Shop shop) throws InvalidShopException {
        ShopResult result = new ShopResult();
        ItemStack foundItem = shop.getItem();
        result.shopOwner = shop.getOwner().getName();
        result.type = foundItem.getType().name();
        result.itemName = initialCaps(foundItem.getType().name());
        result.stock = shop.getRemainingStock();
        result.space = shop.getRemainingSpace();
        result.price = shop.getPrice();

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
