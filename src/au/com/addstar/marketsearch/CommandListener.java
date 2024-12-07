package au.com.addstar.marketsearch;

import au.com.addstar.marketsearch.MarketSearch.ShopResult;
import au.com.addstar.marketsearch.SlimefunNameDB.sfDBItem;
import au.com.addstar.monolith.lookup.Lookup;
import com.google.common.base.Strings;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import org.apache.commons.lang.StringUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import com.ghostchu.quickshop.api.shop.ShopType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

class CommandListener implements CommandExecutor {
    private final MarketSearch plugin;

    public CommandListener(MarketSearch plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String commandLabel,
                             String[] args) {
        final String action = (args.length > 0) ? args[0].toUpperCase() : "";
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
                String lastarg = null;

                if (args.length > 2) {
                    lastarg = args[args.length - 1];
                }

                if ((StringUtils.isNumeric(lastarg))) {
                    // Ending is a page number
                    page = Integer.parseInt(lastarg);
                    if (page < 1) {
                        page = 1;
                    }
                    search = StringUtils.join(args, "_", 1, args.length - 1);
                } else {
                    search = StringUtils.join(args, "_", 1, args.length);
                }

                final ItemStack searchFor = getItem(sender, search);
                if (searchFor == null) {
                    plugin.debug("Warning: getItem() returned null");
                    return true;
                }
                final String finalSearch = search;
                final int finalPage = page;
                Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                    List<ShopResult> resultsUnfiltered;
                    if (action.equals("SELL")) {
                        resultsUnfiltered = plugin.searchMarket(searchFor, ShopType.BUYING);
                    } else {
                        resultsUnfiltered = plugin.searchMarket(searchFor, ShopType.SELLING);
                    }

                    handleSearchResults(action, finalSearch, resultsUnfiltered, sender, searchFor, finalPage);
                });
                return true;
            case "STOCK":
            case "PSTOCK":
                if ((sender instanceof Player) && !sender.hasPermission("marketsearch.stock")) {
                    return false;
                }
                Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                    plugin.debug("Started async task to collect stock levels");
                    List<ShopResult> results;
                    String stockcmd = "";
                    if (action.equals("STOCK")) {
                        // Get all shops
                        results = plugin.getPlayerShops(sender.getName());
                        if (args.length > 1) {
                            stockcmd = args[1].toUpperCase();
                        }
                    } else {
                        // Get all shops (specific player)
                        if (args.length == 1) {
                            plugin.sendHelp(sender);
                            return;
                        } else if (args.length > 2) {
                            stockcmd = args[2].toUpperCase();
                        }
                        results = plugin.getPlayerShops(args[1]);
                    }

                    // Stock summary
                    if (Objects.equals(stockcmd, "")) {
                        int outOfStock = 0;
                        int lessThan10 = 0;
                        int lessThan64 = 0;
                        int stackOrMore = 0;
                        for (ShopResult result : results) {
                            if (result.stock == 0) {
                                outOfStock++;
                            } else if (result.stock < 10) {
                                lessThan10++;
                            } else if (result.stock < 64) {
                                lessThan64++;
                            } else {
                                stackOrMore++;
                            }
                        }
                        if (action.equals("STOCK")) {
                            sender.sendMessage(ChatColor.GREEN + "Your QuickShop stock levels:");
                        } else {
                            sender.sendMessage(ChatColor.GREEN + args[1] + "'s QuickShop stock levels:");
                        }
                        sender.sendMessage(ChatColor.YELLOW + " - Total QuickShops owned: " + ChatColor.WHITE
                              + results.size());
                        if (results.size() > 0) {
                            sender.sendMessage(ChatColor.YELLOW + " - Shops with no stock: " + ChatColor.WHITE
                                  + outOfStock);
                            sender.sendMessage(ChatColor.YELLOW + " - Stock less than 10: " + ChatColor.WHITE
                                  + lessThan10);
                            sender.sendMessage(ChatColor.YELLOW + " - Stock between 10-63: " + ChatColor.WHITE
                                  + lessThan64);
                            sender.sendMessage(ChatColor.YELLOW + " - Stock amount of 64+: " + ChatColor.WHITE
                                  + stackOrMore);
                        } else {
                            if (action.equals("PSTOCK")) {
                                sender.sendMessage(ChatColor.GRAY + "(NOTE: Player names are case sensitive)");
                            }
                        }
                    } else {
                        if (!stockcmd.equals("EMPTY") && !stockcmd.equals("LOWEST")) {
                            plugin.sendHelp(sender);
                            return;
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
                        results.sort(MarketSearch.ShopResultSort.ByStock);

                        int count = 0;
                        for (ShopResult result : results) {
                            // Drop out if:
                            //    we reach the result limit; or
                            //    we find a shop with stock and we only want empty ones
                            if (count >= 15) {
                                break;
                            }
                            if (stockcmd.equals("EMPTY") && result.stock > 0) {
                                break;
                            }

                            if (stockcmd.equals("LOWEST") || result.stock == 0) {
                                count++;
                                sender.sendMessage(
                                      ChatColor.GREEN + " - " + ChatColor.AQUA + result.itemName
                                            + ChatColor.GREEN + ": " + ChatColor.YELLOW + "$" + result.price
                                            + ChatColor.GREEN + "  (" + result.stock + " left)");
                            }
                        }

                        if (count == 0) {
                            sender.sendMessage(ChatColor.RED + "None.");
                        }
                    }
                });
                break;
            case "TPTO":
                if ((sender instanceof Player)) {
                    if (!sender.hasPermission("marketsearch.tpto")) {
                        return false;
                    }
                    if (args.length >= 6) {
                        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Teleporting you to " + args[1] + "'s shop...");

                        // Get location of market shop
                        World w = Bukkit.getWorld(args[2]);
                        double x = Double.parseDouble(args[3]);
                        double y = Double.parseDouble(args[4]);
                        double z = Double.parseDouble(args[5]);
                        Location loc = new Location(w, x, y, z);
                        Block b = loc.getBlock();

                        // If it's still a chest, we teleport to the player to the location in front of the chest
                        if ((b.getType() == Material.CHEST) || (b.getType() == Material.TRAPPED_CHEST) ||
                                (b.getType() == Material.BARREL)) {
                            BlockFace bf = ((Directional) b.getBlockData()).getFacing();
                            Block sign = b.getRelative(bf);
                            Location signloc = sign.getLocation();
                            signloc.setDirection(bf.getOppositeFace().getDirection());
                            signloc.add(0.5, 0, 0.5);
                            final Player player = (Player) sender;

                            if (signloc.getWorld().getBlockAt(signloc.getBlockX(), signloc.getBlockY(), signloc.getBlockZ()).isEmpty()) {
                                if (!player.hasPermission("marketsearch.tptodelay.bypass")) {
                                    // Add a teleport delay before teleporting the player to check for movement
                                    // to prevent the player using this to avoid death
                                    final Location lastLocation = player.getLocation();
                                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&6Teleportation will commence in &c3 seconds&6. Don't move."));
                                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                        if (player.isOnline()) {
                                            if (lastLocation.getBlock().equals(player.getLocation().getBlock())) {
                                                player.sendMessage(ChatColor.GOLD + "Teleportation commencing...");
                                                player.teleport(signloc);
                                            } else {
                                                player.sendMessage(ChatColor.RED + "Teleportation aborted because you moved.");
                                            }
                                        }
                                    }, 60L);
                                } else {
                                    // Teleport the player immediately if they have bypass permission
                                    player.teleport(signloc);
                                }
                            } else {
                                sender.sendMessage(ChatColor.RED
                                      + "Sorry, unable to find a clear location to send you to.");
                                shopNotFound(loc);
                                return true;
                            }
                        } else {
                            sender.sendMessage(ChatColor.RED + "Sorry, unable to find that shop.");
                            shopNotFound(loc);
                            return true;
                        }
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "This command cannot be run from console");
                    return true;
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
                    if (StringUtils.isNumeric(args[1])) {
                        debugLevel = Integer.parseInt(args[1]);
                    } else {
                        sender.sendMessage(ChatColor.RED + "Debug level should be an integer, not " + args[1]);
                    }
                }

                if (args.length == 1 && plugin.isDebugEnabled()) {
                    plugin.setDebugEnabled(false);
                    sender.sendMessage(ChatColor.RED + "MS Debug is now off");
                } else {
                    plugin.setDebugEnabled(true, debugLevel);
                    sender.sendMessage(ChatColor.RED + "MS Debug is now on, debug level " + debugLevel);
                }
                break;
            default:
                plugin.sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleSearchResults(String action, String search, List<ShopResult> resultsUnfiltered,
                                     CommandSender sender, ItemStack searchFor, int page) {
        List<ShopResult> results;
        String filterText = getFilterText(search);
        if (resultsUnfiltered.size() > 0 && !Strings.isNullOrEmpty(filterText)) {
            // Filter the results to only keep those that contain filterText for an enchant or potion
            results = new ArrayList<>();
            filterText = filterText.toLowerCase();

            for (ShopResult result : resultsUnfiltered) {
                if (result.enchanted) {
                    String ench = plugin.getEnchantText(result.enchants);
                    if (ench == null || ench.toLowerCase().contains(filterText)) {
                        results.add(result);
                    }
                    continue;
                }

                if (result.potion) {
                    if (result.potionType == null || result.potionType.toLowerCase().contains(filterText)) {
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

        String initialCapsName = ChatColor.AQUA + MarketSearch.initialCaps(searchFor.getType().name());
        sfDBItem sfitem = null;

        if (plugin.sfEnabled) {
            sfitem = plugin.getSlimefunItemType(searchFor);
            if (sfitem != null) {
                initialCapsName = ChatColor.GOLD + "Slimefun: "
                        + ChatColor.YELLOW + MarketSearch.initialCaps(sfitem.sfname);
            }
        }

        if (page > pages) {
            if (page > 1) {
                sender.sendMessage(ChatColor.RED + "No more results found; "
                      + "use /ms find " + initialCapsName + " " + (page - 1));
            } else {
                sender.sendMessage(ChatColor.RED + "Sorry, no results found for " + initialCapsName);
            }
            return;
        }

        StringBuilder extrainfo = new StringBuilder();
        if (plugin.sfEnabled && sfitem != null) {
            extrainfo.append(sfitem.fullname);
            plugin.debug(initialCapsName + " (" + extrainfo + ")");
        } else {
            Set<String> names = Lookup.findNameByItem(searchFor.getType());
            names.forEach(s -> extrainfo.append(StringUtils.trim(s)).append(", "));
            plugin.debug(initialCapsName + " aliases: " + extrainfo);
        }

        // Show header on results
        sender.sendMessage(ChatColor.GREEN + "Page " + page + "/" + pages + ": "
            + ChatColor.YELLOW + initialCapsName + ChatColor.GOLD + " - "
            + ChatColor.WHITE + extrainfo);

        if (results.size() > 0) {
            String ownerstr;
            String extraInfo = "";
            int start = (perPage * (page - 1));
            int end = start + perPage - 1;
            for (int x = start; x <= end; x++) {
                if (x > (results.size() - 1)) {
                    break;        // Don't go beyond the end of the results
                }
                ShopResult result = results.get(x);
                ownerstr = ChatColor.AQUA + result.plotOwner;

                if (result.enchanted) {
                    String enchantType = "??UnknownType??";
                    if (result.enchants != null) {
                        enchantType = plugin.getEnchantText(result.enchants);
                    }
                    extraInfo = ChatColor.DARK_PURPLE + " [" + ChatColor.LIGHT_PURPLE + enchantType
                          + ChatColor.DARK_PURPLE + "]";
                    extraInfo = extraInfo.replace("/", ChatColor.DARK_PURPLE + "/" + ChatColor.LIGHT_PURPLE);
                } else {

                    if (result.potion) {
                        String potionType = "??UnknownPotion??";
                        if (result.potionType != null) {
                            potionType = result.potionType;
                        }
                        extraInfo = ChatColor.DARK_PURPLE + " [" + ChatColor.LIGHT_PURPLE + potionType
                              + ChatColor.DARK_PURPLE + "]";
                    }
                }

                String stockdisplay;
                if (action.equals("SELL")) {
                    stockdisplay = ChatColor.DARK_GREEN + "(" + ChatColor.GREEN + result.space + " slots"
                          + ChatColor.DARK_GREEN + ")";
                } else {
                    stockdisplay = ChatColor.DARK_GREEN + "(" + ChatColor.GREEN + result.stock + " left"
                          + ChatColor.DARK_GREEN + ")";
                }

                ComponentBuilder row = new ComponentBuilder(" - ").color(ChatColor.GREEN);
                String command = "";
                BaseComponent[] hover = new ComponentBuilder("No location found...").create();
                if (result.shopLocation.getWorld() != null) {
                    command = "/ms tpto "
                          + result.shopOwner + " " + result.shopLocation.getWorld().getName() + " "
                          + result.shopLocation.getBlockX() + " " + result.shopLocation.getBlockY() + " "
                          + result.shopLocation.getBlockZ();
                    hover = new ComponentBuilder("Click to teleport to " + result.shopOwner + "'s shop")
                          .color(ChatColor.DARK_PURPLE)
                          .italic(true)
                          .create();
                }
                row.event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
                row.event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));

                if (sender instanceof Player) {
                    row.append(ownerstr + " ").color(ChatColor.GREEN);
                    row.append("$" + result.price + extraInfo).color(ChatColor.YELLOW);
                    row.append(" " + stockdisplay).color(ChatColor.GREEN);
                    sender.spigot().sendMessage(row.create());
                } else {
                    // Output raw messages to console
                    sender.sendMessage(ChatColor.GREEN + ownerstr + " " + ChatColor.YELLOW + "$" + result.price
                          + extraInfo + ChatColor.GREEN + " " + stockdisplay);
                }
            }
        } else {
            if (action.equals("SELL")) {
                sender.sendMessage(ChatColor.RED + "Sorry, there are no shops buying " + initialCapsName);
            } else {
                sender.sendMessage(ChatColor.RED + "Sorry, no stock available in any shop for " + initialCapsName);
            }
        }
    }

    private ItemStack getItem(CommandSender sender, String search) {
        if (search.equalsIgnoreCase("hand")) {
            if (!(sender instanceof Player)) {
                return null;
            }
            Player ply = (Player) sender;
            ItemStack hand = ply.getInventory().getItemInMainHand();
            if (hand.getType() != Material.AIR) {
                return hand.clone();
            } else {
                sender.sendMessage(ChatColor.RED + "You need to be holding an item first!");
                return null;
            }
        } else {
            try {
                if ((plugin.sfEnabled) && (search.substring(0, 3).equals("sf_"))) {
                    // Searching for Slimefun item
                    if (search.length() > 3) {
                        String sfname = search.substring(3).toUpperCase();
                        sfDBItem sfitem = plugin.sfNameDB.getSFItem(sfname);
                        if (sfitem != null) {
                            // Return the Slimefun item for searching
                            return plugin.makeSlimefunItem(new ItemStack(sfitem.mat, 1), sfitem);
                        }
                    }
                    sender.sendMessage(ChatColor.RED + "Invalid Slimefun item name");
                    return null;
                } else {
                    // Vanilla item search
                    Material searchFor = getItem(search);
                    if (searchFor == null) {
                        sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
                        return null;
                    }
                    return new ItemStack(searchFor, 1);
                }
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Invalid item name or ID");
                plugin.debug("Exception caught: " + e.getCause());
                plugin.debug(e.getMessage());
                plugin.debug(e.getStackTrace().toString());
            }
        }
        return null;
    }

    private Material getItem(String search) {
        String[] parts = getSearchParts(search);
        String itemName = parts[0];

        Material def = getMaterial(itemName);
        if (def == null) {
            plugin.debug("Warning: getMaterial() returned null for: " + itemName);
            return null;
        }

        // Check if we should override the data value with one supplied
        if (parts.length > 1) {
            try {
                return def;
            } catch (NumberFormatException e) {
                plugin.debug("Warning: NumberFormatException caught in getItem()");
            }
        }
        return def;
    }

    private String getFilterText(String search) {
        String[] parts = getSearchParts(search);
        if (parts.length > 1 && !StringUtils.isNumeric(parts[1])) {
            // Filter Text is present
            return parts[1];
        }
        return "";
    }

    private Material getMaterial(String name) {
        // Bukkit name
        Material mat = Material.getMaterial(name.toUpperCase());
        if (mat != null) {
            return mat;
        }
        // ItemDB
        return Lookup.findItemByName(name);
    }

    private String[] getSearchParts(String search) {
        // Split on the colon
        String[] parts = search.split(":");
        String itemName = parts[0];
        String itemEnchant;

        if (parts.length > 1 && !StringUtils.isNumeric(parts[1])) {
            itemEnchant = parts[1];
        } else {
            itemEnchant = "";
        }

        // Auto-change carrots to carrots and potatoes to potato
        if (itemName.equalsIgnoreCase("carrots")) {
            parts[0] = "CARROT";
        }
        if (itemName.equalsIgnoreCase("potatoes")) {
            parts[0] = "POTATO";
        }
        if (itemName.equalsIgnoreCase("diamond_pick")) {
            parts[0] = "DIAMOND_PICKAXE";
        }
        if (itemName.toLowerCase().contains("arrow")) {
            if (parts.length > 1) {
                parts[0] = Material.TIPPED_ARROW.name().toLowerCase();
            }
        }
        if (itemEnchant.equalsIgnoreCase("efficiency")) {
            parts[1] = "eff";
        }
        if (itemEnchant.equalsIgnoreCase("fortune")) {
            parts[1] = "fort";
        }
        if (itemEnchant.equalsIgnoreCase("respiration")) {
            parts[1] = "air";
        }
        if (itemEnchant.equalsIgnoreCase("sharpness")) {
            parts[1] = "dmg";
        }
        if (itemEnchant.equalsIgnoreCase("silktouch")) {
            parts[1] = "silk";
        }
        if (itemEnchant.equalsIgnoreCase("silk_touch")) {
            parts[1] = "silk";
        }
        if (itemEnchant.equalsIgnoreCase("unbreaking")) {
            parts[1] = "dura";
        }
        return parts;
    }

    private void shopNotFound(Location loc) {
        if (loc.getWorld() == null) {
            plugin.warn("Error: Unable to find shop at: world is null ");
        } else {
            plugin.warn("Error: Unable to find shop at: "
                  + loc.getWorld().getName() + " " + loc.getBlockX() + " " + loc.getBlockY() + " "
                  + loc.getBlockZ());
        }
    }
}
