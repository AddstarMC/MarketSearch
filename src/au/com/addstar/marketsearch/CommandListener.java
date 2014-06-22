package au.com.addstar.marketsearch;

import java.util.Collections;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.maxgamer.QuickShop.Shop.ShopType;

import au.com.addstar.marketsearch.MarketSearch.ShopResult;
import au.com.addstar.marketsearch.MarketSearch.ShopResultSort;

public class CommandListener implements CommandExecutor {
	private MarketSearch plugin;

	public CommandListener(MarketSearch plugin) {
		this.plugin = plugin;
	}
	
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
		String action = "";
		if (args.length > 0) {
			action = args[0].toUpperCase();
		}
		
		switch(action) {
		case "FIND":
		case "SELL":
		case "BUY":
			if ((sender instanceof Player)) {
				if (!plugin.RequirePermission((Player) sender, "marketsearch.find")) { return false; }
			}
			
			if (args.length == 1) {
				sender.sendMessage(ChatColor.RED + "Please specify an item to search for.");
				return true;
			}
			
			// Build search string from args (but drop first arg)
			String search = "";
			for (int x = 1; x < args.length; x++) {
				search += args[x];
			}

			// Fix for redstone torches
			//if (search.contains("REDSTONE_TORCH") || search.contains("REDSTONETORCH")) { search = "REDSTONE_TORCH_ON"; }
			
			// Validate the material and perform the search
			ItemStack searchfor;
			try {
				searchfor = plugin.EssPlugin.getItemDb().get(search, 1);
			} catch (Exception e) {
				sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
				return true;
			}
			
			if (searchfor != null) {
				sender.sendMessage(ChatColor.GREEN + "Searching for: " + 
						ChatColor.YELLOW + "(" + searchfor.getTypeId() + ":" + searchfor.getData().getData() + ") " + 
						ChatColor.WHITE + plugin.EssPlugin.getItemDb().names(searchfor));

				List<ShopResult> results;
				if (action.equals("SELL")) {
					results = plugin.SearchMarket(searchfor, ShopType.BUYING);
				} else {
					results = plugin.SearchMarket(searchfor, ShopType.SELLING);
				}

				if (results.size() > 0) {
					int cnt = 0;
					String ownerstr;
					String ench;
					Boolean enchfound = false;
					for (ShopResult result : results) {
						// Cap results at 10
						if (cnt > 11) { break; }
						if (result.PlotOwner.equals(result.ShopOwner)) {
							ownerstr = ChatColor.AQUA + result.PlotOwner;
						} else {
							ownerstr = ChatColor.AQUA + result.PlotOwner + ChatColor.BLUE + " (" + result.ShopOwner + ")";
						}
						
						if (result.Enchanted) {
							ench = " " + ChatColor.LIGHT_PURPLE + "(*)" + " ";
							enchfound = true;
						} else {
							ench = "";
						}

						String stockdisplay;
						if (action == "SELL") {
							stockdisplay = "(" + result.Space + " slots)";
						} else {
							stockdisplay = "(" + result.Stock + " left)";
						}
						
						sender.sendMessage(
								ChatColor.GREEN + " - " + ownerstr + 
					    		ChatColor.GREEN + ": " + 
					    		ChatColor.YELLOW + "$" + result.Price + ench + 
					    		ChatColor.GREEN + "  " + stockdisplay);
						cnt++;
					}
					
					// If enchanted items are in the results, tell them
					if (enchfound) {
						sender.sendMessage(ChatColor.LIGHT_PURPLE + "(*) Indicates an enchanted item");
					}
				} else {
					if (action.equals("SELL")) {
						sender.sendMessage(ChatColor.RED + "Sorry, there are no shops buying that.");
					} else {
						sender.sendMessage(ChatColor.RED + "Sorry, no stock available in any shop.");
					}
				}
			} else {
				sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
			}
			break;
			
		case "STOCK":
		case "PSTOCK":
			if ((sender instanceof Player)) {
				if (!plugin.RequirePermission((Player) sender, "marketsearch.stock")) { return false; }
			}

			List<ShopResult> results;
			String stockcmd = "";
			if (action.equals("STOCK")) {
				// Get all shops
				results = plugin.getPlayerShops(sender.getName());
				if (args.length > 1) stockcmd = args[1].toUpperCase();
			} else {
				// Get all shops (specific player)
				if (args.length == 1) {
					plugin.SendHelp(sender);
					return true;
				}
				else if (args.length > 2) {
					stockcmd = args[2].toUpperCase();
				}
				results = plugin.getPlayerShops(args[1]);
			}

			// Stock summary
			if (stockcmd == "") {
				int OutOfStock = 0;
				int LessThan10 = 0;
				int LessThan64 = 0;
				int StackOrMore = 0;
				for (ShopResult result : results) {
					if (result.Stock == 0) {
						OutOfStock++;
					}
					else if (result.Stock < 10) {
						LessThan10++;
					}
					else if (result.Stock < 64) {
						LessThan64++;
					}
					else {
						StackOrMore++;
					}
				}
				if (action.equals("STOCK")) {
					sender.sendMessage(ChatColor.GREEN + "Your QuickShop stock levels:");
				} else {
					sender.sendMessage(ChatColor.GREEN + args[1] + "'s QuickShop stock levels:");
				}
				sender.sendMessage(ChatColor.YELLOW + " - Total QuickShops owned: " + ChatColor.WHITE + results.size());
				if (results.size() > 0) {
					sender.sendMessage(ChatColor.YELLOW + " - Shops with no stock: " + ChatColor.WHITE + OutOfStock);
					sender.sendMessage(ChatColor.YELLOW + " - Stock less than 10: " + ChatColor.WHITE + LessThan10);
					sender.sendMessage(ChatColor.YELLOW + " - Stock between 10-63: " + ChatColor.WHITE + LessThan64);
					sender.sendMessage(ChatColor.YELLOW + " - Stock amount of 64+: " + ChatColor.WHITE + StackOrMore);
				} else {
					if (action.equals("PSTOCK")) {
						sender.sendMessage(ChatColor.GRAY + "(NOTE: Player names are case sensitive)");
					}
				}
				return true;
			} else {
				if (!stockcmd.equals("EMPTY") && !stockcmd.equals("LOWEST")) {
					plugin.SendHelp(sender);
					return true;
				}

				if (stockcmd.equals("EMPTY")) {
					if (action.equals("STOCK")) {
						sender.sendMessage(ChatColor.GREEN + "Your empty QuickShops:");
					} else {
						sender.sendMessage(ChatColor.GREEN + args[1] + "'s empty QuickShops:");
					}
				} else { 
					if (action.equals("STOCK")) {
						sender.sendMessage(ChatColor.GREEN + "Your lowest stocked QuickShops:");
					} else {
						sender.sendMessage(ChatColor.GREEN + args[1] + "'s lowest stocked QuickShops:");
					}					
				}
				
				// First sort results by price 
				Collections.sort(results, ShopResultSort.ByStock);

				int count = 0;
				for (ShopResult result : results) {
					// Drop out if:
					//    we reach the result limit; or
					//    we find a shop with stock and we only want empty ones
					if (count >= 15) { break; }
					if (stockcmd.equals("EMPTY") && result.Stock > 0) { break; }
					
					if (stockcmd.equals("LOWEST") || (stockcmd.equals("EMPTY") && result.Stock == 0)) {
						count++;
						String stockdisplay;
						if (action == "SELL") {
							stockdisplay = "(" + result.Space + " slots)";
						} else {
							stockdisplay = "(" + result.Stock + " left)";
						}
						
						sender.sendMessage(
								ChatColor.GREEN + " - " + 
					    		ChatColor.AQUA + result.ItemName + 
					    		ChatColor.GREEN + ": " + 
					    		ChatColor.YELLOW + "$" + result.Price + 
					    		ChatColor.GREEN + "  " + stockdisplay);
					}
				}
				
				if (count == 0) {
					sender.sendMessage(ChatColor.RED + "None.");
				}
			}
			break;
			
		case "REPORT":
			if ((sender instanceof Player)) {
				if (!plugin.RequirePermission((Player) sender, "marketsearch.report")) { return false; }
			}
			
		default:
			plugin.SendHelp(sender);
			break;
		}
		return true;
	}
}
