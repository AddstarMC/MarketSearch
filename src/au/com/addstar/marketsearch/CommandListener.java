package au.com.addstar.marketsearch;

import au.com.addstar.marketsearch.MarketSearch.ShopResult;
import au.com.addstar.marketsearch.MarketSearch.ShopResultSort;
import au.com.addstar.monolith.lookup.Lookup;
import com.google.common.base.Strings;
import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.maxgamer.QuickShop.Shop.ShopType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

class CommandListener implements CommandExecutor {
	private final MarketSearch plugin;

	public CommandListener(MarketSearch plugin) {
		this.plugin = plugin;
	}
	
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
		String action = "";
		if (args.length > 0) {
			action = args[0].toUpperCase();
		}

		switch (action) {
			case "FIND":
			case "SELL":
			case "BUY":
				if ((sender instanceof Player)) {
					if (!plugin.RequirePermission((Player) sender, "marketsearch.find")) {
						return false;
					}
				}

				if (args.length == 1) {
					sender.sendMessage(ChatColor.GREEN +
							"Please specify an item to search for, " +
							"or 'hand' to search for what you are currently holding (/ms find hand)");
					sender.sendMessage(ChatColor.GREEN +
							" - filter weapon enchants using /ms find diamond_sword:fire");
					sender.sendMessage(ChatColor.GREEN +
							" - find spawn eggs using /ms find cow_spawn_egg or chicken_spawn_egg");

					return true;
				}

				// Grab item name (can contain spaces) and page number at the end (if applicable)
				int page = 1;
				String search = "";
				String lastarg = null;

				if (args.length > 2) {
					lastarg = args[args.length - 1];
				}

				if ((lastarg != null) && (StringUtils.isNumeric(lastarg))) {
					// Ending is a page number
					page = Integer.valueOf(lastarg);
					if (page < 1) page = 1;
					search = StringUtils.join(args, "_", 1, args.length - 1);
				} else {
					search = StringUtils.join(args, "_", 1, args.length);
				}

				// Validate the material and perform the search
				// But first, check for 'hand' argument
				Material searchfor;
				if (search.equalsIgnoreCase("hand")) {
					Player ply = (Player) sender;
					ItemStack hand = ply.getInventory().getItemInMainHand();
					if (hand != null && hand.getType() != Material.AIR) /* Empty hand is Material.AIR */
						searchfor = hand.getType();
					else {
						sender.sendMessage(ChatColor.RED + "You need to be holding an item first!");
						return true;
					}
				} else {
					try {
						searchfor = plugin.getItem(search);
					} catch (Exception e) {
						sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
						plugin.Debug("Exception caught: " + e.getCause());
						plugin.Debug(e.getMessage());
						return true;
					}
				}

				if (searchfor != null) {
					List<ShopResult> resultsUnfiltered;
					if (action.equals("SELL")) {
						resultsUnfiltered = plugin.SearchMarket(new ItemStack(searchfor, 1), ShopType.BUYING);
					} else {
						resultsUnfiltered = plugin.SearchMarket(new ItemStack(searchfor, 1), ShopType.SELLING);
					}

					List<ShopResult> results;

					String filterText = plugin.getFilterText(search);
					if (resultsUnfiltered.size() > 0 && !Strings.isNullOrEmpty(filterText)) {

						// Filter the results to only keep those that contain filterText for an enchant or potion
						results = new ArrayList<>();
						filterText = filterText.toLowerCase();

						for (ShopResult result : resultsUnfiltered) {

							if (result.Enchanted) {
								String ench = plugin.getEnchantText(result.Enchants);
								if (ench == null || ench.toLowerCase().contains(filterText)) {
									results.add(result);
								}
								continue;
							}

							if (result.SpawnEgg) {
								if (result.SpawnType == null || result.SpawnType.toString().toLowerCase().contains(filterText)) {
									results.add(result);
								}
								continue;
							}

							if (result.Potion) {
								if (result.PotionType == null || result.PotionType.toLowerCase().contains(filterText)) {
									results.add(result);
								}
							}

						}
					} else {
						// Do not filter
						results = resultsUnfiltered;
					}


					int perpage = 10;
					int pages = (int) Math.ceil((double) results.size() / perpage);

					if (page > pages) {
						if (page > 1)
							sender.sendMessage(ChatColor.RED + "No more results found for " + searchfor.name() + "; try page " + (page - 1));
						else
							sender.sendMessage(ChatColor.RED + "Sorry, no results found for " + searchfor.name());
						return true;
					}

					Set<String> names = Lookup.findNameByItem(searchfor);
					plugin.Debug(searchfor.name() + " aliases: " + String.join(", ", names));
					sender.sendMessage(ChatColor.GREEN + "Page " + page + "/" + pages + ": " +
							ChatColor.YELLOW + "(" + searchfor.name() + ") " +
							ChatColor.WHITE + StringUtils.join(names, ", "));

					if (results.size() > 0) {
						String ownerstr;
						String extraInfo = "";
						int start = (perpage * (page - 1));
						int end = start + perpage - 1;
						for (int x = start; x <= end; x++) {
							if (x > (results.size() - 1)) break;        // Don't go beyond the end of the results

							ShopResult result = results.get(x);
							ownerstr = ChatColor.AQUA + result.PlotOwner;

							if (result.Enchanted) {
								String enchantType = "??UnknownType??";
								if (result.Enchants != null) {
									enchantType = plugin.getEnchantText(result.Enchants);
								}
								extraInfo = ChatColor.DARK_PURPLE + " [" + ChatColor.LIGHT_PURPLE + enchantType + ChatColor.DARK_PURPLE + "]";
								extraInfo = extraInfo.replace("/", ChatColor.DARK_PURPLE + "/" + ChatColor.LIGHT_PURPLE);
							} else {
								if (result.SpawnEgg) {
									String spawnType = "??UnknownMob??";
									if (result.SpawnType != null) {
										spawnType = result.SpawnType.toString();
									}
									extraInfo = ChatColor.DARK_PURPLE + " [" + ChatColor.LIGHT_PURPLE + spawnType + ChatColor.DARK_PURPLE + "]";
								}

								if (result.Potion) {
									String potionType = "??UnknownPotion??";
									if (result.PotionType != null) {
										potionType = result.PotionType;
									}
									extraInfo = ChatColor.DARK_PURPLE + " [" + ChatColor.LIGHT_PURPLE + potionType + ChatColor.DARK_PURPLE + "]";
								}
							}

							String stockdisplay;
							if (action.equals("SELL")) {
								stockdisplay = ChatColor.DARK_GREEN + "(" + ChatColor.GREEN + result.Space + " slots" + ChatColor.DARK_GREEN + ")";
							} else {
								stockdisplay = ChatColor.DARK_GREEN + "(" + ChatColor.GREEN + result.Stock + " left" + ChatColor.DARK_GREEN + ")";
							}

							sender.sendMessage(
									ChatColor.GREEN + " - " + ownerstr +
											ChatColor.GREEN + ": " +
											ChatColor.YELLOW + "$" + result.Price + extraInfo +
											ChatColor.GREEN + " " + stockdisplay);
						}
					} else {
						if (action.equals("SELL")) {
							sender.sendMessage(ChatColor.RED + "Sorry, there are no shops buying " + searchfor.name());
						} else {
							sender.sendMessage(ChatColor.RED + "Sorry, no stock available in any shop for " + searchfor.name());
						}
					}
				} else {
					sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
					plugin.Debug("Warning: getItem() returned null");
				}
				break;

			case "STOCK":
			case "PSTOCK":
				if ((sender instanceof Player)) {
					if (!plugin.RequirePermission((Player) sender, "marketsearch.stock")) {
						return false;
					}
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
					} else if (args.length > 2) {
						stockcmd = args[2].toUpperCase();
					}
					results = plugin.getPlayerShops(args[1]);
				}

				// Stock summary
				if (Objects.equals(stockcmd, "")) {
					int OutOfStock = 0;
					int LessThan10 = 0;
					int LessThan64 = 0;
					int StackOrMore = 0;
					for (ShopResult result : results) {
						if (result.Stock == 0) {
							OutOfStock++;
						} else if (result.Stock < 10) {
							LessThan10++;
						} else if (result.Stock < 64) {
							LessThan64++;
						} else {
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
					results.sort(ShopResultSort.ByStock);

					int count = 0;
					for (ShopResult result : results) {
						// Drop out if:
						//    we reach the result limit; or
						//    we find a shop with stock and we only want empty ones
						if (count >= 15) {
							break;
						}
						if (stockcmd.equals("EMPTY") && result.Stock > 0) {
							break;
						}

						if (stockcmd.equals("LOWEST") || (stockcmd.equals("EMPTY") && result.Stock == 0)) {
							count++;
							sender.sendMessage(
									ChatColor.GREEN + " - " +
											ChatColor.AQUA + result.ItemName +
											ChatColor.GREEN + ": " +
											ChatColor.YELLOW + "$" + result.Price +
											ChatColor.GREEN + "  (" + result.Stock + " left)");
						}
					}

					if (count == 0) {
						sender.sendMessage(ChatColor.RED + "None.");
					}
				}
				break;

			case "REPORT":
				if ((sender instanceof Player)) {
					if (!plugin.RequirePermission((Player) sender, "marketsearch.report")) {
						return false;
					}
				}
				break;
			case "DEBUG":
				if ((sender instanceof Player)) {
					if (!plugin.RequirePermission((Player) sender, "marketsearch.debug")) {
						return false;
					}
				}
				if (plugin.isDebugEnabled()) {
					plugin.setDebugEnabled(false);
				} else {
					plugin.setDebugEnabled(true);
				}
				sender.sendMessage(ChatColor.RED + "MS Debug is "+ plugin.isDebugEnabled());
				break;
			default:
			plugin.SendHelp(sender);
			break;
		}
		return true;
	}
}
