package au.com.addstar.marketsearch.pricereduction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceMathTest {

    private static final double PCT = 0.02;
    private static final double MIN_DROP = 0.01;
    private static final double FLOOR = 1.0;
    private static final double EPS = 1e-9;

    @Test
    void appliesPercentageWhenAboveMinDrop() {
        // 2% of 100 = 2 (> minDrop), so 100 -> 98
        assertEquals(98.0, PriceMath.computeNewPrice(100.0, PCT, MIN_DROP, FLOOR), EPS);
    }

    @Test
    void appliesMinDropWhenPercentageTooSmall() {
        // 2% of 1.20 = 0.024 (> 0.01) -> still percentage. Use a value where pct < minDrop:
        // 2% of 0.40 = 0.008 < 0.01, but 0.40 is below floor 1.0 so unchanged. Use floor 0.0:
        double newPrice = PriceMath.computeNewPrice(0.40, PCT, MIN_DROP, 0.0);
        assertEquals(0.39, newPrice, EPS); // 0.40 - max(0.008, 0.01) = 0.39
    }

    @Test
    void neverDropsBelowFloor() {
        // 2% of 1.10 = 0.022 -> 1.078, above floor 1.0; rounded to 2dp -> 1.08
        assertEquals(1.08, PriceMath.computeNewPrice(1.10, PCT, MIN_DROP, FLOOR), EPS);
        // A drop that would cross the floor clamps exactly to the floor
        assertEquals(FLOOR, PriceMath.computeNewPrice(1.005, PCT, MIN_DROP, FLOOR), EPS);
    }

    @Test
    void resultIsRoundedToTwoDecimalPlaces() {
        // The reported case: 4.99 - 2% = 4.8902, which must be stored as 4.89.
        assertEquals(4.89, PriceMath.computeNewPrice(4.99, PCT, MIN_DROP, FLOOR), EPS);
        assertEquals(1.23, PriceMath.round2dp(1.2349));
        assertEquals(1.24, PriceMath.round2dp(1.2350));
    }

    @Test
    void atOrBelowFloorIsUnchanged() {
        assertEquals(1.0, PriceMath.computeNewPrice(1.0, PCT, MIN_DROP, FLOOR), EPS);
        assertEquals(0.5, PriceMath.computeNewPrice(0.5, PCT, MIN_DROP, FLOOR), EPS);
    }

    @Test
    void wouldReduceReflectsActualChange() {
        assertTrue(PriceMath.wouldReduce(100.0, PCT, MIN_DROP, FLOOR));
        assertFalse(PriceMath.wouldReduce(1.0, PCT, MIN_DROP, FLOOR));
        assertFalse(PriceMath.wouldReduce(0.5, PCT, MIN_DROP, FLOOR));
    }

    @Test
    void tinyPriceWithZeroFloorStillUsesMinDrop() {
        // 2% of 0.05 = 0.001 < minDrop 0.01 -> 0.05 - 0.01 = 0.04
        assertEquals(0.04, PriceMath.computeNewPrice(0.05, PCT, MIN_DROP, 0.0), EPS);
    }

    @Test
    void shopKeyIsCanonical() {
        assertEquals("market;10;64;-20", PriceMath.shopKey("market", 10, 64, -20));
    }
}
