package au.com.addstar.marketsearch;

import com.ghostchu.quickshop.api.shop.Shop;

/**
 * Which side of the market a search is looking for.
 *
 * <p>This used to be QuickShop's {@code ShopType} enum, but as of QuickShop-Hikari 6.3 shop types
 * are a pluggable registry ({@code IShopType}) rather than a fixed enum. MarketSearch only ever
 * cares about the buying/selling distinction, so it keeps its own two-valued type and asks each
 * shop via the {@code isBuying()}/{@code isSelling()} predicates that survive that refactor.
 */
public enum SearchType {
    /** Shops that buy items from players. */
    BUYING,
    /** Shops that sell items to players. */
    SELLING;

    /**
     * @return true if the given shop trades on this side of the market.
     */
    public boolean matches(Shop<?, ?> shop) {
        return this == BUYING ? shop.isBuying() : shop.isSelling();
    }
}
