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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SpawnEggMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.maxgamer.QuickShop.QuickShop;
import org.maxgamer.QuickShop.Shop.Shop;
import org.maxgamer.QuickShop.Shop.ShopChunk;
import org.maxgamer.QuickShop.Shop.ShopManager;
import org.maxgamer.QuickShop.Shop.ShopType;

import au.com.addstar.monolith.lookup.Lookup;
import au.com.addstar.monolith.util.PotionUtil;
import us.talabrek.ultimateskyblock.api.uSkyBlockAPI;

public class MarketSearch extends JavaPlugin {

	public boolean isDebugEnabled() {
		return DebugEnabled;
	}

	public void setDebugEnabled(boolean debugEnabled) {
		DebugEnabled = debugEnabled;
	}

	private boolean DebugEnabled = false;
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
		Integer ItemID;
		byte Data;
		String Type;
		Integer Stock;
		Integer Space;
		Double Price;
		Boolean Enchanted = false;
		Map<Enchantment, Integer> Enchants = null;
		Boolean Potion = false;
		String PotionType = null;
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
                Log("PlotProvider: uSkyBlock hooked");
            }
        }

		if(pm.getPlugin("PlotSquared") != null){
            Plugin plotsquared = pm.getPlugin("PlotSquared");
            if(plotsquared != null && plotsquared.isEnabled()){
                plotProvider = new PlotSquaredPlotProvider();
				Log("PlotProvider: PlotSquared hooked");
            }
        }
		
		LoadEnchants();
		
		MarketWorld = "market";
		
		getCommand("marketsearch").setExecutor(new CommandListener(this));
		getCommand("marketsearch").setAliases(Collections.singletonList("ms"));
		
		Log(pdfFile.getName() + " " + pdfFile.getVersion() + " has been enabled");
	}
		
	@Override
	public void onDisable() {
		// Nothing yet
	}

	public static class ShopResultSort {
		static final Comparator<ShopResult> ByPrice = (shop1, shop2) -> {
            //Log("Compare: " + shop1.ShopOwner + " $" + shop1.Price + " / " + shop2.ShopOwner + " $" + shop2.Price);
            if (shop1.Price.equals(shop2.Price)) {
                //Log(" - Same!");
                if (shop1.Stock > shop2.Stock) {
                    return -1;
                }

                if (shop1.Stock < shop2.Stock) {
                    return 1;
                } else {
                    return 0;
                }

            }

            //Log(" - Not same!");
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

		if (map != null) {
			for (Entry<ShopChunk, HashMap<Location, Shop>> chunks : map.entrySet()) {

				for (Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
					Shop shop = inChunk.getValue();
					ItemStack shopItem = shop.getItem();

					if (shopItem.getType() != SearchItem.getType()) {
						continue;
					}    // Wrong item

					// Durability is deprecated in 1.13
					//
					// Only compare data/durability for items with no real durability (blocks, etc)
					// if (SearchItem.getType().getMaxDurability() == 0) {
					// 	if (shopItem.getDurability() != SearchItem.getDurability()) {
					// 		continue;
					// 	}
					// }

					if (SearchType == ShopType.SELLING && shop.getRemainingStock() == 0) {
						continue;
					}    // No stock
					if (SearchType == ShopType.BUYING && shop.getRemainingSpace() == 0) {
						continue;
					}    // No space
					if (shop.getShopType() != SearchType) {
						// Wrong shop type
						continue;
					}

					ShopResult result = StoreResult(shop);

					// Is this item enchanted?
					if (shopItem.getEnchantments().size() > 0) {
						result.Enchants = shopItem.getEnchantments();
						result.Enchanted = true;
					}

					// Is this an enchanted book?
					if (shopItem.getType() == Material.ENCHANTED_BOOK) {

						EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) shopItem.getItemMeta();

						if (bookMeta.hasStoredEnchants()) {
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

					String owner = plotProvider.getPlotOwner(shop.getLocation());
					if (owner != null) {
						result.PlotOwner = owner;
						results.add(result);
					} else {
						Warn("Unable to find plot! " + shop.getLocation().toString());
					}
				}
			}
		}else{
			Warn("Quickshop returned NO Shops");
		}

		if (DebugEnabled) {
			logger.info("Sorting " + results.size() + " results for item " + SearchItem.getType().name());
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
			for (Entry<ShopChunk, HashMap<Location, Shop>> chunks : QSSM.getShops(MarketWorld).entrySet()) {

				for (Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
					Shop shop = inChunk.getValue();
					if (shop.getOwner().getName().equalsIgnoreCase(player)) {

						ShopResult result = StoreResult(shop);

						String owner = plotProvider.getPlotOwner(shop.getLocation());
						if (owner != null) {
							result.PlotOwner = owner;
							results.add(result);
						} else {
							Warn("Unable to find plot! " + shop.getLocation().toString());
						}
					}
				}
			}
		}else{
			Warn("Quickshop returned NO Shops");

		}
		return results;
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
	
	private void LoadEnchants() {
        EnchantMap.clear();
		EnchantMap.put(Enchantment.ARROW_DAMAGE, "dmg");
		EnchantMap.put(Enchantment.ARROW_FIRE, "fire");
		EnchantMap.put(Enchantment.ARROW_INFINITE, "inf");
		EnchantMap.put(Enchantment.ARROW_KNOCKBACK, "knock");
		EnchantMap.put(Enchantment.DAMAGE_ALL, "dmg");
		EnchantMap.put(Enchantment.DAMAGE_ARTHROPODS, "bane");
		EnchantMap.put(Enchantment.DAMAGE_UNDEAD, "smite");
		EnchantMap.put(Enchantment.DEPTH_STRIDER, "strider");
		EnchantMap.put(Enchantment.DIG_SPEED, "eff");
		EnchantMap.put(Enchantment.DURABILITY, "dura");
		EnchantMap.put(Enchantment.FIRE_ASPECT, "fire");
		EnchantMap.put(Enchantment.FROST_WALKER, "frost");
		EnchantMap.put(Enchantment.KNOCKBACK, "knock");
		EnchantMap.put(Enchantment.LOOT_BONUS_BLOCKS, "fort");
		EnchantMap.put(Enchantment.LOOT_BONUS_MOBS, "fort");
		EnchantMap.put(Enchantment.LUCK, "luck");
		EnchantMap.put(Enchantment.LURE, "lure");
		EnchantMap.put(Enchantment.MENDING, "mend");
		EnchantMap.put(Enchantment.OXYGEN, "air");
		EnchantMap.put(Enchantment.PROTECTION_ENVIRONMENTAL, "prot");
		EnchantMap.put(Enchantment.PROTECTION_EXPLOSIONS, "blast");
		EnchantMap.put(Enchantment.PROTECTION_FALL, "fall");
		EnchantMap.put(Enchantment.PROTECTION_FIRE, "fireprot");
		EnchantMap.put(Enchantment.PROTECTION_PROJECTILE, "proj");
		EnchantMap.put(Enchantment.SILK_TOUCH, "silk");
		EnchantMap.put(Enchantment.THORNS, "thorn");
		EnchantMap.put(Enchantment.WATER_WORKER, "aqua");
	}
	
	private void Log(String data) {
		logger.info(pdfFile.getName() + " " + data);
	}

	private void Warn(String data) {
		logger.warning(pdfFile.getName() + " " + data);
	}
	
	public void Debug(String data) {
		if (DebugEnabled) {
			logger.info(pdfFile.getName() + " " + data);
		}
	}

	/*
	 * Check if the player has the specified permission
	 */
    private boolean HasPermission(Player player, String perm) {
        // Real player
        return !(player instanceof Player) || player.hasPermission(perm);
// Console has permissions for everything
    }
	
	/*
	 * Check required permission and send error response to player if not allowed
	 */
	public boolean RequirePermission(Player player, String perm) {
		if (!HasPermission(player, perm)) {
			if (player instanceof Player) {
				player.sendMessage(ChatColor.RED + "Sorry, you do not have permission for this command.");
				return false;
			}
		}
		return true;
	}

	/*
	 * Check if player is online
	 */
	public boolean IsPlayerOnline(String player) {
        if (player == null) {
            return false;
        }
        return !Objects.equals(player, "") && this.getServer().getPlayer(player) != null;
    }

	public Material GetMaterial(String name) {
		Material mat = Material.matchMaterial(name);
		if (mat != null) {
			return mat;
		}
		return null;
	}
	
	public void SendHelp(CommandSender sender) {
		sender.sendMessage(ChatColor.GREEN + "Available MarketSearch commands:");

		if (!(sender instanceof Player) || (HasPermission((Player) sender, "marketsearch.find"))) {
			sender.sendMessage(ChatColor.AQUA + "/ms find <item> :" + ChatColor.WHITE + " Find items being sold in the market");
			sender.sendMessage(ChatColor.AQUA + "/ms sell <item> :" + ChatColor.WHITE + " Find items being bought in the market");
			sender.sendMessage(ChatColor.AQUA + "/ms find/sell hand :" + ChatColor.WHITE + " Search using the item you are currently holding");
		}
		
		if (!(sender instanceof Player) || (HasPermission((Player) sender, "marketsearch.stock"))) {
			sender.sendMessage(ChatColor.AQUA + "/ms stock :" + ChatColor.WHITE + " Get a summary of your stock levels");
			sender.sendMessage(ChatColor.AQUA + "/ms stock empty :" + ChatColor.WHITE + " List your shops with NO stock");
			sender.sendMessage(ChatColor.AQUA + "/ms stock lowest :" + ChatColor.WHITE + " List your shops with lowest stock");
		}
		if (!(sender instanceof Player) || (HasPermission((Player) sender, "marketsearch.stock.others"))) {
			sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> :" + ChatColor.WHITE + " Get another player's stock levels");
			sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> empty :" + ChatColor.WHITE + " Other player's shops with NO stock");
			sender.sendMessage(ChatColor.AQUA + "/ms pstock <player> lowest :" + ChatColor.WHITE + " Other player's shops with lowest stock");
		}
		if(!(sender instanceof Player) || (HasPermission((Player) sender, "marketsearch.debug"))) {
			sender.sendMessage(ChatColor.AQUA + "/ms debug :" + ChatColor.WHITE + " Switch debugging on and off as a toggle");
		}
	}

    public Material getItem(String search)
	{
		// Split the search term on the colon to obtain the material name and optionally a data value or text filter
		// The data value could text to filter on, e.g. diamond_sword:fire or diamond_shovel:eff
		// The data value is not required to be present

		String[] parts = getSearchParts(search);
		String itemName = parts[0];

        Material def = getMaterial(itemName);
		if (def == null) {
			Debug("Warning: getMaterial() returned null for: " + itemName);
			return null;
		}
		Debug("getMaterial returned: " + def.name());

		// Check if we should override the data value with one supplied
		if(parts.length > 1) {
			try {
				if (!StringUtils.isNumeric(parts[1])) {
					// For enchanted tools, the user is allowed to filter for a given enchant
					// For spawn eggs, the user is allowed to specify the mob name instead of ID
					// Just return the generic material for now
					return def;
                } else {
                    return def;
                }
            } catch (NumberFormatException e) {
				Debug("Warning: NumberFormatException caught in getItem()");
            }
        }
        return def;
    }

	public String getFilterText(String search) {
		String[] parts = getSearchParts(search);
		if (parts.length > 1 && !StringUtils.isNumeric(parts[1])) {
			// Filter Text is present
			return parts[1];
		}
		return "";
	}

	private String[] getSearchParts(String search) {
		// Split on the colon
		String[] parts = search.split(":");
		String itemname = parts[0];

		// Auto-change carrot to carrot_item (ID 391) and potato to potato_item (ID 392)
		if (itemname.equalsIgnoreCase("carrot"))
			parts[0] = "CARROT_ITEM";

		if (itemname.equalsIgnoreCase("potato"))
			parts[0] = "POTATO_ITEM";

		// Auto-change spawn_egg to monster_egg
		if (itemname.equalsIgnoreCase("spawn_egg"))
			parts[0] = "MONSTER_EGG";

		// Auto-change some potion search terms
		// Splash potion
		if (itemname.contains("splash"))
			parts[0] = "438";

		// Lingering potion
		if (itemname.contains("linger"))
			parts[0] = "441";

		return parts;
	}

    private Material getMaterial(String name)
	{
		// Bukkit name
		Material mat = Material.getMaterial(name.toUpperCase());
		if (mat != null)
            return mat;


		// ItemDB
		return Lookup.findItemByName(name);
	}

	private String InitialCaps(String itemName) {
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

	private ShopResult StoreResult(Shop shop) {
		ShopResult result = new ShopResult();
		ItemStack foundItem = shop.getItem();
		result.ShopOwner = shop.getOwner().getName();
        result.Type = foundItem.getType().name();

		if (result.Data > 0)
			result.ItemName = InitialCaps(foundItem.getType().name()) + " (" + result.ItemID + ":" + result.Data + ")";
		else
			result.ItemName = InitialCaps(foundItem.getType().name());

		result.Stock = shop.getRemainingStock();
		result.Space = shop.getRemainingSpace();
		result.Price = shop.getPrice();

		return result;
	}

}
