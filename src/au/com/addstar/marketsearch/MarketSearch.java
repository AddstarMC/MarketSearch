package au.com.addstar.marketsearch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;

import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;

import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.maxgamer.QuickShop.QuickShop;
import org.maxgamer.QuickShop.Shop.Shop;
import org.maxgamer.QuickShop.Shop.ShopChunk;
import org.maxgamer.QuickShop.Shop.ShopManager;
import org.maxgamer.QuickShop.Shop.ShopType;

import com.earth2me.essentials.IEssentials;
import com.worldcretornica.plotme.Plot;
import com.worldcretornica.plotme.PlotManager;

public class MarketSearch extends JavaPlugin {
	public static MarketSearch instance;
	
	public static Economy econ = null;
	public static Permission perms = null;
	public static Chat chat = null;
	public boolean VaultEnabled = false;
	public boolean DebugEnabled = false;
	public String MarketWorld = null;
	public ShopManager QSSM = null;
	public Map<Enchantment, String> EnchantMap = new HashMap<Enchantment, String>();
	
	private static final Logger logger = Logger.getLogger("Minecraft");
	public PluginDescriptionFile pdfFile = null;
	public PluginManager pm = null;

	public IEssentials EssPlugin;
	
	static class ShopResult {
		String PlotOwner;
		String ShopOwner;
		String ItemName;
		Integer ItemID;
		String Type;
		Integer Stock;
		Integer Space;
		Double Price;
		Boolean Enchanted = false;
		Map<Enchantment, Integer> Enchants = null;
	}
	
	@Override
	public void onEnable(){
		// Register necessary events
		pdfFile = this.getDescription();
		pm = this.getServer().getPluginManager();
		QSSM = QuickShop.instance.getShopManager();
		LoadEnchants();
		
		MarketWorld = "market";
		
		getCommand("marketsearch").setExecutor(new CommandListener(this));
		getCommand("marketsearch").setAliases(Arrays.asList("ms"));
		
		final Plugin ess = this.pm.getPlugin("Essentials");
		if (ess != null) {
			EssPlugin = (IEssentials) ess;
			Log("Found Essentials - It will be used for item lookup");
		} else {
			EssPlugin = null;
			Log("Essentials not found! Using native Bukkit item lookup");
		}
		
		Log(pdfFile.getName() + " " + pdfFile.getVersion() + " has been enabled");
	}
		
	@Override
	public void onDisable() {
		// Nothing yet
	}

	public static class ShopResultSort {
		public static Comparator<ShopResult> ByPrice = new Comparator<ShopResult>() {
			@Override
			public int compare(ShopResult shop1, ShopResult shop2) {
				//Log("Compare: " + shop1.ShopOwner + " $" + shop1.Price + " / " + shop2.ShopOwner + " $" + shop2.Price);
				if (shop1.Price.equals(shop2.Price)) {
					//Log(" - Same!");
					if (shop1.Stock > shop2.Stock) {
						return -1;
					} else {
						return 1;
					}
				}
				
				//Log(" - Not same!");
				if (shop1.Price > shop2.Price) {
					return 1;
				} else {
					return -1;
				}
			}
		};

		public static Comparator<ShopResult> ByPriceDescending = new Comparator<ShopResult>() {
			@Override
			public int compare(ShopResult shop1, ShopResult shop2) {
				
				if (shop1.Price.equals(shop2.Price)) {
					if (shop1.Space > shop2.Space) {
						return -1;
					} else {
						return 1;
					}
				}
				
				if (shop1.Price > shop2.Price) {
					return -1;
				} else {
					return 1;
				}
			}
		};
		
		public static Comparator<ShopResult> ByStock = new Comparator<ShopResult>() {
			@Override
			public int compare(ShopResult shop1, ShopResult shop2) {
				if (shop1.Stock == shop2.Stock) return 0;
				
				if (shop1.Stock > shop2.Stock) {
					return 1;
				} else {
					return -1;
				}
			}
		};
	}

	public List<ShopResult> SearchMarket(ItemStack SearchItem, ShopType SearchType) {
		List<ShopResult> results = new ArrayList<ShopResult>(); 
		for(Entry<ShopChunk, HashMap<Location, Shop>> chunks : QSSM.getShops(MarketWorld).entrySet()) {
			
		    for(Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
		    	Shop shop = inChunk.getValue();

		    	if (shop.getItem().getTypeId() != SearchItem.getTypeId()) { continue; }	// Wrong item

		    	// Only compare data/durability for items with no real durability (blocks, etc)
		    	if (SearchItem.getType().getMaxDurability() == 0) {
		    		if (shop.getItem().getDurability() != SearchItem.getDurability()) { continue; }
		    	}

		    	if (SearchType == ShopType.SELLING && shop.getRemainingStock() == 0) { continue; }	// No stock
		    	if (SearchType == ShopType.BUYING && shop.getRemainingSpace() == 0) { continue; }	// No space
		    	if (shop.getShopType() != SearchType) { continue; }									// Wrong shop type
		    	
		    	ShopResult result = new ShopResult();
			    result.ShopOwner = shop.getOwner();
			    result.ItemName = shop.getItem().getType().name();
			    result.ItemID = shop.getItem().getTypeId();
			    result.Stock = shop.getRemainingStock();
			    result.Space = shop.getRemainingSpace();
			    result.Price = shop.getPrice();

			    // Is this item enchanted?
			    if (shop.getItem().getEnchantments().size() > 0) {
				    result.Enchants = shop.getItem().getEnchantments();
			    	result.Enchanted = true;
			    }

			    Plot p = PlotManager.getPlotById(shop.getLocation());
			    if (p != null) {
			    	result.PlotOwner = p.owner;
			    	results.add(result);
			    } else {
			    	Warn("Unable to find plot! " + shop.getLocation().toString());
			    }
		    }
		}

		// Order results here
		if (SearchType == ShopType.SELLING) {
			Collections.sort(results, ShopResultSort.ByPrice);
		} else {
			Collections.sort(results, ShopResultSort.ByPriceDescending);
		}
		return results;
	}

	public List<ShopResult> getPlayerShops(String player) {
		List<ShopResult> results = new ArrayList<ShopResult>();
		for(Entry<ShopChunk, HashMap<Location, Shop>> chunks : QSSM.getShops(MarketWorld).entrySet()) {
			
		    for(Entry<Location, Shop> inChunk : chunks.getValue().entrySet()) {
		    	Shop shop = inChunk.getValue();
		    	if (shop.getOwner().equalsIgnoreCase(player)) {
			    	ShopResult result = new ShopResult();
				    result.ShopOwner = shop.getOwner();
				    result.ItemName = shop.getItem().getType().name();
				    result.ItemID = shop.getItem().getTypeId();
				    result.Stock = shop.getRemainingStock();
				    result.Price = shop.getPrice();
	
				    Plot p = PlotManager.getPlotById(shop.getLocation());
				    if (p != null) {
				    	result.PlotOwner = p.owner;
				    	results.add(result);
				    } else {
				    	Warn("Unable to find plot! " + shop.getLocation().toString());
				    }
		    	}
		    }
		}
		return results;
	}

	public String getEnchantText(Map<Enchantment, Integer> enchants) {
        List<String> elist = new ArrayList<String>();
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
        EnchantMap.put(Enchantment.DIG_SPEED, "eff");
        EnchantMap.put(Enchantment.DURABILITY, "dura");
        EnchantMap.put(Enchantment.FIRE_ASPECT, "fire");
        EnchantMap.put(Enchantment.KNOCKBACK, "knock");
        EnchantMap.put(Enchantment.LOOT_BONUS_BLOCKS, "fort");
        EnchantMap.put(Enchantment.LOOT_BONUS_MOBS, "fort");
        EnchantMap.put(Enchantment.OXYGEN, "air");
        EnchantMap.put(Enchantment.PROTECTION_ENVIRONMENTAL, "prot");
        EnchantMap.put(Enchantment.PROTECTION_EXPLOSIONS, "blast");
        EnchantMap.put(Enchantment.PROTECTION_FALL, "fall");
        EnchantMap.put(Enchantment.PROTECTION_PROJECTILE, "proj");
        EnchantMap.put(Enchantment.SILK_TOUCH, "silk");
        EnchantMap.put(Enchantment.THORNS, "thorn");
        EnchantMap.put(Enchantment.WATER_WORKER, "aqua");
	}
	
	public void Log(String data) {
		logger.info(pdfFile.getName() + " " + data);
	}

	public void Warn(String data) {
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
	public boolean HasPermission(Player player, String perm) {
		if (player instanceof Player) {
			// Real player
			if (player.hasPermission(perm)) {
				return true;
			}
		} else {
			// Console has permissions for everything
			return true;
		}
		return false;
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
		if (player == null) { return false; }
		if (player == "") { return false; }
		if (this.getServer().getPlayer(player) != null) {
			// Found player.. they must be online!
			return true;
		}
		return false;
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
	}
	
}
