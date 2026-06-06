package au.com.addstar.marketsearch.pricereduction;

import org.bukkit.Location;

/**
 * Pure, side-effect-free helpers for the price reduction routine. Kept free of Bukkit runtime
 * state (other than the {@link Location} formatting helper) so the core maths can be unit tested.
 */
public final class PriceMath {

    private PriceMath() {
    }

    /**
     * Computes the new price for a single daily reduction.
     *
     * <p>The drop is {@code max(currentPrice * percent, minDrop)}, clamped so the result never falls
     * below {@code minPrice}. If the shop is already at or below the floor the price is returned
     * unchanged.
     *
     * @param currentPrice the shop's current price
     * @param percent      fractional reduction, e.g. 0.02 for 2%
     * @param minDrop      absolute minimum drop to apply when a reduction happens
     * @param minPrice     the global floor; the price will never be reduced below this
     * @return the new price (== currentPrice when no reduction should occur)
     */
    public static double computeNewPrice(double currentPrice, double percent, double minDrop, double minPrice) {
        if (currentPrice <= minPrice) {
            return currentPrice;
        }
        double drop = Math.max(currentPrice * percent, minDrop);
        double newPrice = currentPrice - drop;
        if (newPrice < minPrice) {
            newPrice = minPrice;
        }
        return newPrice;
    }

    /**
     * @return true if a reduction would actually change the price for this shop.
     */
    public static boolean wouldReduce(double currentPrice, double percent, double minDrop, double minPrice) {
        return computeNewPrice(currentPrice, percent, minDrop, minPrice) < currentPrice;
    }

    /**
     * Canonical key for a shop derived from its block location: {@code world;x;y;z}.
     */
    public static String shopKey(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    /**
     * Canonical key for a shop from a Bukkit {@link Location}.
     */
    public static String shopKey(Location loc) {
        String world = (loc.getWorld() != null) ? loc.getWorld().getName() : "null";
        return shopKey(world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
