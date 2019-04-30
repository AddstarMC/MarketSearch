package au.com.addstar.marketsearch;

import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Logger;

import au.com.addstar.marketsearch.PlotProviders.PlotProvider;
import au.com.addstar.marketsearch.PlotProviders.PlotSquaredPlotProvider;

import au.com.addstar.marketsearch.PlotProviders.USkyBlockProvider;
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

import au.com.addstar.monolith.util.PotionUtil;
import us.talabrek.ultimateskyblock.api.uSkyBlockAPI;

public class MarketSearch extends JavaPlugin {

	public boolean isDebugEnabled() {
		return DebugEnabled;
	}

	public void setDebugEnabled(boolean debugEnabled) {
		DebugEnabled = debugEnabled;
	}

	public void setDebugEnabled(boolean debugEnabled, int debugLevel) {
		DebugEnabled = debugEnabled;
		DebugLevel = debugLevel;
	}

	private boolean DebugEnabled = false;

	// Higher numbers lead to more debug messages
	private int DebugLevel = 1;

	private String MarketWorld = null;
	private ShopManager QSSM = null;
	private PlotProvider plotProvider;
	private final Map<Enchantment, String> EnchantMap = new HashMap<>();
	
	private static final Logger logger = Logger.getLogger("Minecraft");
	private PluginDescriptionFile pdfFile = null;

    static class ShopResult {
		String PlotOwner;
		String ShopOwner;
		String ItemName;
		String Type;
		Integer Stock;
		Integer Space;
		Double Price;
		Boolean Enchanted = false;
		Map<Enchantment, Integer> Enchants = null;
		Boolean Potion = false;
		String PotionType = null;
		Location ShopLocation;
	}
	
	@Override
	public void onEnable(){
		// Register necessary events
		pdfFile = this.getDescription();
        PluginManager pm = this.getServer().getPluginManager();
		QSSM = QuickShop.instance.getShopManager();
		
        if(pm.getPlugin("uSkyBlock") != null){
            Plugin uSkyBlock = pm.getPlugin("uSkyBlock");
            if(uSkyBlock != null && uSkyBlock.isEnabled()){
                plotProvider = new USkyBlockProvider((uSkyBlockAPI)uSkyBlock);
                log("PlotProvider: uSkyBlock hooked");
            }
        }

		if(pm.getPlugin("PlotSquared") != null){
            Plugin plotsquared = pm.getPlugin("PlotSquared");
            if(plotsquared != null && plotsquared.isEnabled()){
                plotProvider = new PlotSquaredPlotProvider();
				log("PlotProvider: PlotSquared hooked");
            }
        }
		
		loadEnchants();
		
		MarketWorld = "market";
		String commandText = "marketsearch";
		PluginCommand command = getCommand(commandText);
		if(command !=null) {
			command.setExecutor(new CommandListener(this));
			command.setAliases(Collections.singletonList("ms"));
		}else{
			warn("MarketSearch command not found  - plugin will not execute commands -check yml");
		}
		
		log(pdfFile.getName() + " " + pdfFile.getVersion() + " has been enabled");
	}
		
	@Override
	public void onDisable() {
		// Nothing yet
	}

	public static class ShopResultSort {
		static final Comparator<ShopResult> ByPrice = (shop1, shop2) -> {
            //log("Compare: " + shop1.ShopOwner + " $" + shop1.Price + " / " + shop2.ShopOwner + " $" + shop2.Price);
            if (shop1.Price.equals(shop2.Price)) {
                //log(" - Same!");
                if (shop1.Stock > shop2.Stock) {
                    return -1;
                }

                if (shop1.Stock < shop2.Stock) {
                    return 1;
                } else {
                    return 0;
                }

            }

            //log(" - Not same!");
            if (shop1.Price > shop2.Price) {
                return 1;
            }

            if (shop1.Price < shop2.Price) {
                return -1;
            } else {
                return 0;
            }

        };

		static final Comparator<ShopResult> ByPriceDescending = (shop1, shop2) -> {

            if (shop1.Price.equals(shop2.Price)) {
                if (shop1.Space > shop2.Space) {
                    return -1;
                }

                if (shop1.Space < shop2.Space) {
                    return 1;
                } else {
                    return 0;
                }
            }

            if (shop1.Price > shop2.Price) {
                return -1;
            }

            if (shop1.Price < shop2.Price) {
                return 1;
            } else {
                return 0;
            }
        };
		
		public static final Comparator<ShopResult> ByStock = (shop1, shop2) -> {
            if (Objects.equals(shop1.Stock, shop2.Stock)) return 0;

            if (shop1.Stock > shop2.Stock) {
                return 1;
            } else {
                return -1;
            }
        };
	}

	public List<ShopResult> SearchMarket(ItemStack SearchItem, ShopType SearchType) {
		List<ShopResult> results = new ArrayList<>();
		HashMap<ShopChunk, HashMap<Location, Shop>> map = QSSM.getShops(MarketWorld);

		int maxDetailedCount = 50;
		int wrongItemCount = 0;
		int noStockCount = 0;
		int noSpaceCount = 0;

		Material itemType = SearchItem.getType();

		if (map != null) {

			if (DebugEnabled) {
				debug("Searching shops for item " + getItemDetails(SearchItem));
			}

			for (Entry<ShopChunk, HashMap<Location, Shop>> chunks : map.entrySet()) {

				for (Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
					Shop shop = inChunk.getValue();
					ItemStack shopItem = shop.getItem();

					if (shopItem.getType() != itemType) {
						// Wrong item
						if (DebugEnabled) {
							wrongItemCount++;
							if (wrongItemCount <= maxDetailedCount && DebugLevel > 1) {
								logger.info("No match to " + shopItem.getType().name() + " in shop at " +
										shop.getLocation().getBlockX() + " " +
										shop.getLocation().getBlockY() + " " +
										shop.getLocation().getBlockZ());

								if (wrongItemCount == maxDetailedCount) {
									logger.info(" ... max wrong item count limit reached; no more items will be logged");
								}
							}
						}
						continue;
					}

					// Durability is deprecated in 1.13
					//
					// Only compare data/durability for items with no real durability (blocks, etc)
					// if (SearchItem.getType().getMaxDurability() == 0) {
					// 	if (shopItem.getDurability() != SearchItem.getDurability()) {
					// 		continue;
					// 	}
					// }

					if (SearchType == ShopType.SELLING && shop.getRemainingStock() == 0) {
						// No stock
						if (DebugEnabled) {
							noStockCount++;
							if (noStockCount <= maxDetailedCount) {
								logger.info("Match found, but no stock in shop at " +
										shop.getLocation().getBlockX() + " " +
										shop.getLocation().getBlockY() + " " +
										shop.getLocation().getBlockZ() + ", shop item " + getItemDetails(shopItem));

								if (noStockCount == maxDetailedCount) {
									logger.info(" ... max no stock count limit reached; no more items will be logged");
								}
							}
						}
						continue;
					}
					if (SearchType == ShopType.BUYING && shop.getRemainingSpace() == 0) {
						// No space
						if (DebugEnabled) {
							noSpaceCount++;
							if (noSpaceCount <= maxDetailedCount) {
								logger.info("Match found, but no space to buy item in shop at " +
										shop.getLocation().getBlockX() + " " +
										shop.getLocation().getBlockY() + " " +
										shop.getLocation().getBlockZ() + ", shop item " + getItemDetails(shopItem));

								if (noSpaceCount == maxDetailedCount) {
									logger.info(" ... max no space count limit reached; no more items will be logged");
								}
							}
						}
						continue;
					}
					if (shop.getShopType() != SearchType) {
						// Wrong shop type
						continue;
					}

					if (DebugEnabled) {
						logger.info("Match found, in shop at " +
								shop.getLocation().getBlockX() + " " +
								shop.getLocation().getBlockY() + " " +
								shop.getLocation().getBlockZ() + "; storing");
					}

					ShopResult result = storeResult(shop);

					// Is this item enchanted?
					if (shopItem.getEnchantments().size() > 0) {
						result.Enchants = shopItem.getEnchantments();
						result.Enchanted = true;
					}

					// Is this an enchanted book?
					if (shopItem.getType() == Material.ENCHANTED_BOOK) {

						EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) shopItem.getItemMeta();

						if (bookMeta != null && bookMeta.hasStoredEnchants()) {
							// Store the enchantment(s)
							result.Enchanted = true;
							result.Enchants = bookMeta.getStoredEnchants();
						} else {
							if (DebugEnabled) {
								logger.info("No stored enchants on book");
							}
						}
					}

					// Is this a potion?
					if (shopItem.getType() == Material.POTION ||
							shopItem.getType() == Material.SPLASH_POTION ||
							shopItem.getType() == Material.LINGERING_POTION) {

						PotionUtil potion = PotionUtil.fromItemStack(shopItem);
						result.Potion = true;
						result.PotionType = potion.toString();
					}

					addshopResult(results, shop, result);

					// Store the shop location so we can teleport the player later
					result.ShopLocation = shop.getLocation();
				}
			}
		}else{
			warn("Quickshop returned NO Shops");
		}

		if (DebugEnabled) {
			if (results.size() == 0)
				logger.info("No results for item " + itemType.name());
			else
				logger.info("Sorting " + results.size() + " results for item " + itemType.name());
		}

		// Order results here
		if (SearchType == ShopType.SELLING) {
			results.sort(ShopResultSort.ByPrice);
		} else {
			results.sort(ShopResultSort.ByPriceDescending);
		}
		return results;
	}

	public List<ShopResult> getPlayerShops(String player) {

		List<ShopResult> results = new ArrayList<>();
		HashMap<ShopChunk, HashMap<Location, Shop>> map = QSSM.getShops(MarketWorld);
		if (map !=null) {
			HashMap<ShopChunk, HashMap<Location, Shop>> shops = QSSM.getShops(MarketWorld);
			if(shops !=null) {
				for (Entry<ShopChunk, HashMap<Location, Shop>> chunks : shops.entrySet()) {
					if (chunks.getValue() != null) {
						for (Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
							Shop shop = inChunk.getValue();
							if (shop.getOwner().getName() != null) {
								if (shop.getOwner().getName().equalsIgnoreCase(player)) {

									ShopResult result = storeResult(shop);

									addshopResult(results, shop, result);
								}
							}
						}
					}
				}
			} else {
				warn("MarketWorld returned NO Shops");
			}
		}else{
			warn("Quickshop returned NO Shops");
		}
		return results;
	}

	private void addshopResult(List<ShopResult> results, Shop shop, ShopResult result) {
		String owner = plotProvider.getPlotOwner(shop.getLocation());
		if (owner != null) {
			result.PlotOwner = owner;
			results.add(result);
		} else {
			warn("Unable to find plot! " + shop.getLocation().toString());
		}
	}

	public String getEnchantText(Map<Enchantment, Integer> enchants) {
        List<String> elist = new ArrayList<>();
        for (Entry<Enchantment, Integer> e: enchants.entrySet()) {
                Enchantment enchant = e.getKey();
                Integer level = e.getValue();
                String abbr = EnchantMap.get(enchant);
                if (abbr == null) {
                        abbr = "??"; 
                }
                elist.add(abbr + level);
        }
        
        // Return sorted string list
        return StringUtils.join(elist.toArray(), "/");
	}

	private void loadEnchants() {
        EnchantMap.clear();
		EnchantMap.put(Enchantment.ARROW_DAMAGE, "pierce");
		EnchantMap.put(Enchantment.ARROW_FIRE, "flame");
		EnchantMap.put(Enchantment.ARROW_INFINITE, "inf");
		EnchantMap.put(Enchantment.ARROW_KNOCKBACK, "punch");
		EnchantMap.put(Enchantment.BINDING_CURSE, "binding");
		EnchantMap.put(Enchantment.CHANNELING, "channel");
		EnchantMap.put(Enchantment.DAMAGE_ALL, "dmg");		// Sharpness
		EnchantMap.put(Enchantment.DAMAGE_ARTHROPODS, "bane");
		EnchantMap.put(Enchantment.DAMAGE_UNDEAD, "smite");
		EnchantMap.put(Enchantment.DEPTH_STRIDER, "strider");
		EnchantMap.put(Enchantment.DIG_SPEED, "eff");
		EnchantMap.put(Enchantment.DURABILITY, "dura");
		EnchantMap.put(Enchantment.FIRE_ASPECT, "fire");
		EnchantMap.put(Enchantment.FROST_WALKER, "frost");
		EnchantMap.put(Enchantment.IMPALING, "impale");
		EnchantMap.put(Enchantment.KNOCKBACK, "knock");
		EnchantMap.put(Enchantment.LOOT_BONUS_BLOCKS, "fort");
		EnchantMap.put(Enchantment.LOOT_BONUS_MOBS, "loot");
		EnchantMap.put(Enchantment.LOYALTY, "loyal");
		EnchantMap.put(Enchantment.LUCK, "luck");
		EnchantMap.put(Enchantment.LURE, "lure");
		EnchantMap.put(Enchantment.MENDING, "mend");
		EnchantMap.put(Enchantment.OXYGEN, "air");
		EnchantMap.put(Enchantment.PROTECTION_ENVIRONMENTAL, "prot");
		EnchantMap.put(Enchantment.PROTECTION_EXPLOSIONS, "blast");
		EnchantMap.put(Enchantment.PROTECTION_FALL, "fall");
		EnchantMap.put(Enchantment.PROTECTION_FIRE, "fireprot");
		EnchantMap.put(Enchantment.PROTECTION_PROJECTILE, "proj");
		EnchantMap.put(Enchantment.RIPTIDE, "rip");
		EnchantMap.put(Enchantment.SILK_TOUCH, "silk");
		EnchantMap.put(Enchantment.SWEEPING_EDGE, "sweep");
		EnchantMap.put(Enchantment.THORNS, "thorn");
		EnchantMap.put(Enchantment.VANISHING_CURSE, "vanish");
		EnchantMap.put(Enchantment.WATER_WORKER, "aqua");
	}

	private String getItemDetails(ItemStack item) {
		Material itemType = item.getType();
		StringBuilder description = new StringBuilder(itemType.name());

		if (item.hasItemMeta()) {
			ItemMeta shopItemMeta = item.getItemMeta();
			if(shopItemMeta != null) {
				Map<Enchantment, Integer> enchants = shopItemMeta.getEnchants();

				for (final Entry<Enchantment, Integer> entries : enchants.entrySet()) {
					description.append(", enchantment: ").append(entries.getKey().getKey()).append(" ").append(entries.getValue());
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
		if (DebugEnabled) {
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
			sender.sendMessage(ChatColor.AQUA + "/ms find <item> :" + ChatColor.WHITE + " Find items being sold in the market");
			sender.sendMessage(ChatColor.AQUA + "/ms sell <item> :" + ChatColor.WHITE + " Find items being bought in the market");
			sender.sendMessage(ChatColor.AQUA + "/ms find/sell hand :" + ChatColor.WHITE + " Search using the item type you are currently holding");
			sender.sendMessage(ChatColor.AQUA + "/ms find/sell handexact :" + ChatColor.WHITE +
					" Search using the exact item (including all properties including durability) you are currently holding");
			sender.sendMessage(ChatColor.GREEN +	" - filter weapon enchants using /ms find diamond_sword:fire");
			sender.sendMessage(ChatColor.GREEN +	" - find spawn eggs using /ms find cow_spawn_egg or chicken_spawn_egg");
		}

		if (!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.stock"))) {
			sender.sendMessage(ChatColor.AQUA + "/ms stock :" + ChatColor.WHITE + " Get a summary of your stock levels");
			sender.sendMessage(ChatColor.AQUA + "/ms stock empty :" + ChatColor.WHITE + " List your shops with NO stock");
			sender.sendMessage(ChatColor.AQUA + "/ms stock lowest :" + ChatColor.WHITE + " List your shops with lowest stock");
		}
		if (!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.stock.others"))) {
			sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> :" + ChatColor.WHITE + " Get another player's stock levels");
			sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> empty :" + ChatColor.WHITE + " Other player's shops with NO stock");
			sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> lowest :" + ChatColor.WHITE + " Other player's shops with lowest stock");
		}
		if(!(sender instanceof Player) || (hasPermission((Player) sender, "marketsearch.debug"))) {
			sender.sendMessage(ChatColor.AQUA + "/ms debug :" + ChatColor.WHITE + " Switch debugging on and off as a toggle");
			sender.sendMessage(ChatColor.AQUA + "/ms debug 1 :" + ChatColor.WHITE + " When enabling debug, optionally use 1 for normal logging");
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

	private ShopResult storeResult(Shop shop) {
		ShopResult result = new ShopResult();
		ItemStack foundItem = shop.getItem();
		result.ShopOwner = shop.getOwner().getName();
        result.Type = foundItem.getType().name();
        result.ItemName = initialCaps(foundItem.getType().name());
		result.Stock = shop.getRemainingStock();
		result.Space = shop.getRemainingSpace();
		result.Price = shop.getPrice();

		return result;
	}

}
