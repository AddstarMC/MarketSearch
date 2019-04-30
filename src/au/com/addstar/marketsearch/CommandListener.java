package au.com.addstar.marketsearch;

import au.com.addstar.marketsearch.MarketSearch.ShopResult;
import au.com.addstar.marketsearch.MarketSearch.ShopResultSort;
import au.com.addstar.monolith.lookup.Lookup;

import com.google.common.base.Strings;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;

import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.maxgamer.QuickShop.Shop.ShopType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static au.com.addstar.marketsearch.MarketSearch.initialCaps;

class CommandListener implements CommandExecutor {
    private final MarketSearch plugin;

    public CommandListener(MarketSearch plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String commandLabel, @NotNull String[] args) {
        String action = "";
        if (args.length > 0) {
            action = args[0].toUpperCase();
        }

        switch (action) {
            case "FIND":
            case "SELL":
            case "BUY":
                if ((sender instanceof Player) && !sender.hasPermission("marketsearch.find")) {
                        return false;
                }

                if (args.length == 1) {
                    plugin.sendHelp(sender);
                    return true;
                }

                // Grab item name (can contain spaces) and page number at the end (if applicable)
                int page = 1;
                String search;
                String lastArg = null;

                if (args.length > 2) {
                    lastArg = args[args.length - 1];
                }

                if ((StringUtils.isNumeric(lastArg))) {
                    // Ending is a page number
                    page = Integer.valueOf(lastArg);
                    if (page < 1) page = 1;
                    search = StringUtils.join(args, "_", 1, args.length - 1);
                } else {
                    search = StringUtils.join(args, "_", 1, args.length);
                }

                // Validate the material and perform the search
                // But first, check for 'hand' argument
                ItemStack searchfor = getItem(sender, search);
                if (searchfor == null) {
                    plugin.debug("Warning: getItem() returned null");
                    return true;
                }


                List<ShopResult> resultsUnfiltered;
                if (action.equals("SELL")) {
                    resultsUnfiltered = plugin.SearchMarket(searchfor, ShopType.BUYING);
                } else {
                    resultsUnfiltered = plugin.SearchMarket(searchfor, ShopType.SELLING);
                }

                List<ShopResult> results;

                String filterText = getFilterText(search);
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

                int perPage = 10;
                int pages = (int) Math.ceil((double) results.size() / perPage);

                String initialCapsName = initialCaps(searchfor.getType().name());

                if (page > pages) {
                    if (page > 1)
                        sender.sendMessage(ChatColor.RED + "No more results found; " +
                                "use /ms find " + initialCapsName + " " + (page - 1));
                    else
                        sender.sendMessage(ChatColor.RED + "Sorry, no results found for " + initialCapsName);
                    return true;
                }

                Set<String> names = Lookup.findNameByItem(searchfor.getType());
                plugin.debug(initialCapsName + " aliases: " + String.join(", ", names));
                sender.sendMessage(ChatColor.GREEN + "Page " + page + "/" + pages + ": " +
                        ChatColor.YELLOW + "(" + initialCapsName + ") " +
                        ChatColor.WHITE + StringUtils.join(names, ", "));

                if (results.size() > 0) {
                    String ownerStr;
                    String extraInfo = "";
                    int start = (perPage * (page - 1));
                    int end = start + perPage - 1;
                    for (int x = start; x <= end; x++) {
                        if (x > (results.size() - 1))
                            break;        // Don't go beyond the end of the results

                        ShopResult result = results.get(x);
                        ownerStr = ChatColor.AQUA + result.PlotOwner;

                        if (result.Enchanted) {
                            String enchantType = "??UnknownType??";
                            if (result.Enchants != null) {
                                enchantType = plugin.getEnchantText(result.Enchants);
                            }
                            extraInfo = ChatColor.DARK_PURPLE + " [" + ChatColor.LIGHT_PURPLE + enchantType + ChatColor.DARK_PURPLE + "]";
                            extraInfo = extraInfo.replace("/", ChatColor.DARK_PURPLE + "/" + ChatColor.LIGHT_PURPLE);
                        } else {

                            if (result.Potion) {
                                String potionType = "??UnknownPotion??";
                                if (result.PotionType != null) {
                                    potionType = result.PotionType;
                                }
                                extraInfo = ChatColor.DARK_PURPLE + " [" + ChatColor.LIGHT_PURPLE + potionType + ChatColor.DARK_PURPLE + "]";
                            }
                        }

                        String stockDisplay;
                        if (action.equals("SELL")) {
                            stockDisplay = ChatColor.DARK_GREEN + "(" + ChatColor.GREEN + result.Space + " slots" + ChatColor.DARK_GREEN + ")";
                        } else {
                            stockDisplay = ChatColor.DARK_GREEN + "(" + ChatColor.GREEN + result.Stock + " left" + ChatColor.DARK_GREEN + ")";
                        }

                        ComponentBuilder row = new ComponentBuilder(" - ").color(ChatColor.GREEN);
                        if(result.ShopLocation.getWorld() !=null) {
                            row.event(new ClickEvent(
                                    ClickEvent.Action.RUN_COMMAND,
                                    "/ms tpto " +
                                            result.ShopOwner + " " +
                                            result.ShopLocation.getWorld().getName() + " " +
                                            result.ShopLocation.getBlockX() + " " +
                                            result.ShopLocation.getBlockY() + " " +
                                            result.ShopLocation.getBlockZ()));
                            row.event(new HoverEvent(
                                    HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(
                                    "Click to teleport to " + result.ShopOwner + "'s shop")
                                    .color(ChatColor.DARK_PURPLE)
                                    .italic(true)
                                    .create()));
                        }
                        if (sender instanceof Player) {
                            // Only send the basecomponent to players
                            row.append(ownerStr + " ").color(ChatColor.GREEN);
                            row.append("$" + result.Price + extraInfo).color(ChatColor.YELLOW);
                            row.append(" " + stockDisplay).color(ChatColor.GREEN);
                            sender.spigot().sendMessage(row.create());
                        } else {
                            // Output raw messages to console
                            sender.sendMessage(ChatColor.GREEN + ownerStr + " " +
                                    ChatColor.YELLOW + "$" + result.Price + extraInfo +
                                    ChatColor.GREEN + " " + stockDisplay);
                        }
                    }
                } else {
                    if (action.equals("SELL")) {
                        sender.sendMessage(ChatColor.RED + "Sorry, there are no shops buying " + initialCapsName);
                    } else {
                        sender.sendMessage(ChatColor.RED + "Sorry, no stock available in any shop for " + initialCapsName);
                    }
                }
                break;

            case "STOCK":
            case "PSTOCK":
                if ((sender instanceof Player) && !sender.hasPermission("marketsearch.stock")) {
                        return false;
                }
                String stockcmd = "";
                if (action.equals("STOCK")) {
                    // Get all shops
                    results = plugin.getPlayerShops(sender.getName());
                    if (args.length > 1) stockcmd = args[1].toUpperCase();
                } else {
                    // Get all shops (specific player)
                    if (args.length == 1) {
                        plugin.sendHelp(sender);
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
                        plugin.sendHelp(sender);
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

                        if (stockcmd.equals("LOWEST")) {
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

            case "TPTO":
                if ((sender instanceof Player) && !sender.hasPermission("marketsearch.tpto")) {
                        return false;
                } else if(!(sender instanceof Player)){
                    sender.sendMessage(ChatColor.RED + "This command cannot be run from console");
                    return true;
                }
                if (args.length >= 6) {
                    sender.sendMessage(ChatColor.LIGHT_PURPLE + "Teleporting you to " + args[1] + "'s shop...");

                    // Get location of market shop
                    World w = Bukkit.getWorld(args[2]);
                    double x = Double.valueOf(args[3]);
                    double y = Double.valueOf(args[4]);
                    double z = Double.valueOf(args[5]);
                    Location loc = new Location(w, x, y, z);
                    Block b = loc.getBlock();

                    // If it's still a chest, we teleport to the player to the location in front of the chest
                    if ((b.getType() == Material.CHEST) || (b.getType() == Material.TRAPPED_CHEST)) {
                        BlockFace bf = ((Directional) b.getBlockData()).getFacing();
                        Block sign = b.getRelative(bf);
                        Location signloc = sign.getLocation();
                        signloc.setDirection(bf.getOppositeFace().getDirection());
                        signloc.add(0.5, 0, 0.5);

                        Location tmp = signloc.clone();
                        if (tmp.add(0, 1, 0).getBlock().isEmpty()) {
                            ((Player)sender).teleport(signloc);
                        } else {
                            sender.sendMessage(ChatColor.RED + "Sorry, unable to find a clear location to send you to.");
                            shopNotFound(loc);
                            return true;
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + "Sorry, unable to find that shop.");
                        shopNotFound(loc);
                        return true;
                    }
                }
                break;

            case "REPORT":
                if ((sender instanceof Player) && !sender.hasPermission("marketsearch.report")) {
                        return false;
                }
                break;

            case "DEBUG":
                if ((sender instanceof Player) && !sender.hasPermission("marketsearch.debug")) {
                        return false;

                }

                int debugLevel = 1;

                if (args.length > 1) {
                    if (StringUtils.isNumeric(args[1]))
                        debugLevel = Integer.parseInt(args[1]);
                    else
                        sender.sendMessage(ChatColor.RED + "debug level should be an integer, not " + args[1]);
                }

                if (args.length == 1 && plugin.isDebugEnabled()) {
                    plugin.setDebugEnabled(false);
                    sender.sendMessage(ChatColor.RED + "MS debug is now off");
                } else {
                    plugin.setDebugEnabled(true, debugLevel);
                    sender.sendMessage(ChatColor.RED + "MS debug is now on, debug level " + debugLevel);
                }
                break;
            default:
                plugin.sendHelp(sender);
                break;
        }
        return true;
    }

    private void shopNotFound(Location loc){
        if(loc.getWorld() == null){
            plugin.warn("Error: Unable to find shop at: world is null ");
        } else {
            plugin.warn("Error: Unable to find shop at: " +
                    loc.getWorld().getName() + " " +
                    loc.getBlockX() + " " +
                    loc.getBlockY() + " " +
                    loc.getBlockZ());
        }
    }

    private ItemStack getItem(CommandSender sender, String search) {
        ItemStack result;
        if (search.equalsIgnoreCase("hand") || search.equalsIgnoreCase("handexact")) {
            if(!(sender instanceof Player))
                return null;
            Player ply = (Player) sender;
            ItemStack hand = ply.getInventory().getItemInMainHand();
            if (hand.getType() != Material.AIR) /* Empty hand is Material.AIR */
                if(search.equalsIgnoreCase("hand")) {
                    result = new ItemStack(hand.getType(),1);
                }else{
                    result = hand.clone();
                }
            else {
                sender.sendMessage(ChatColor.RED + "You need to be holding an item first!");
                return null;
            }
        } else {
            try {
                Material searchFor = getItem(search);
                if(searchFor == null){
                    sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
                    return null;
                }
                result = new ItemStack(searchFor,1);
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
                plugin.debug("Exception caught: " + e.getCause());
                plugin.debug(e.getMessage());
                return null;
            }
        }
        return result;
    }

    private String getFilterText(String search) {
        String[] parts = getSearchParts(search);
        if (parts.length > 1 && !StringUtils.isNumeric(parts[1])) {
            // Filter Text is present
            return parts[1];
        }
        return "";
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

    private Material getItem(String search)
    {
        // Split the search term on the colon to obtain the material name and optionally a data value or text filter
        // The data value could text to filter on, e.g. diamond_sword:fire or diamond_shovel:eff
        // The data value is not required to be present

        String[] parts = getSearchParts(search);
        String itemName = parts[0];

        Material def = getMaterial(itemName);
        if (def == null) {
            plugin.debug("Warning: getMaterial() returned null for: " + itemName);
            return null;
        }
        plugin.debug("getMaterial returned: " + def.name());

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
                plugin.debug("Warning: NumberFormatException caught in getItem()");
            }
        }
        return def;
    }



    private String[] getSearchParts(String search) {
        // Split on the colon
        String[] parts = search.split(":");
        String itemName = parts[0];
        String itemEnchant;

        if (parts.length > 1 && !StringUtils.isNumeric(parts[1]))
            itemEnchant = parts[1];
        else
            itemEnchant = "";

        // Auto-change carrots to carrots and potatoes to potato
        if (itemName.equalsIgnoreCase("carrots"))
            parts[0] = "CARROT";

        if (itemName.equalsIgnoreCase("potatoes"))
            parts[0] = "POTATO";

        if (itemName.equalsIgnoreCase("diamond_pick"))
            parts[0] = "DIAMOND_PICKAXE";

        if (itemEnchant.equalsIgnoreCase("efficiency"))
            parts[1] = "eff";

        if (itemEnchant.equalsIgnoreCase("fortune"))
            parts[1] = "fort";

        if (itemEnchant.equalsIgnoreCase("respiration"))
            parts[1] = "air";

        if (itemEnchant.equalsIgnoreCase("sharpness"))
            parts[1] = "dmg";

        if (itemEnchant.equalsIgnoreCase("silktouch"))
            parts[1] = "silk";

        if (itemEnchant.equalsIgnoreCase("silk_touch"))
            parts[1] = "silk";

        if (itemEnchant.equalsIgnoreCase("unbreaking"))
            parts[1] = "dura";

        return parts;
    }
}
