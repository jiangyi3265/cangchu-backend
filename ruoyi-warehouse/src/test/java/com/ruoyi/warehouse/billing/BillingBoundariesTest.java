package com.ruoyi.warehouse.billing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BillingBoundariesTest {
    private static Map<String, Object> map(Object... args) { return BillingCalculator.map(args); }
    private static void money(String expected, Object actual) { assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString()))); }

    @Test
    void roundHalfEvenAndPreserveNinePlaceArithmetic() {
        money("3.12", BillingCalculator.moneyRound(BillingCalculator.decimal("3.125")));
        money("3.14", BillingCalculator.moneyRound(BillingCalculator.decimal("3.135")));
        money("-3.12", BillingCalculator.moneyRound(BillingCalculator.decimal("-3.125")));
        money("-3.14", BillingCalculator.moneyRound(BillingCalculator.decimal("-3.135")));
        money("0.3", BillingCalculator.decimal("0.1").add(BillingCalculator.decimal("0.2")));
        assertEquals("-1234567890", BillingCalculator.toRaw(BillingCalculator.decimal("-1.2345678909")));
        assertThrows(IllegalArgumentException.class, () -> BillingCalculator.decimal("NaN"));
        assertThrows(IllegalArgumentException.class, () -> BillingCalculator.decimal("Infinity"));
    }

    @Test
    void recurringExplicitZeroDoesNotDefaultToOne() {
        money("0", BillingCalculator.recurringAmount(map("amount", 25, "quantity", 0)));
        money("25", BillingCalculator.recurringAmount(map("amount", 25)));
        money("25", BillingCalculator.recurringAmount(map("amount", 25, "quantity", "")));
        money("25", BillingCalculator.recurringAmount(map("amount", 25, "quantity", null)));
        money("3.12", BillingCalculator.recurringAmount(map("amount", "1.5625", "quantity", 2)));
    }

    @Test
    void orderTiersCountOrdersAndSubtractAlreadyChargedAmounts() {
        Map<String, Object> rule = map("tiers", List.of(map("from", 1, "rate", "0.05"), map("from", 101, "rate", "0.10")), "splitTiers", true);
        Map<String, Object> quote = BillingCalculator.orderTierAdjustment(150, rule, List.of("5"));
        money("5", quote.get("amount")); money("10", quote.get("cumulativeAmount"));
        assertEquals(150L, quote.get("orderCount"));
        money("0", BillingCalculator.orderTierAdjustment(150, rule, List.of("5", "5")).get("amount"));
        // A lower retrospective rate can produce a credit; it must not be clamped.
        money("-2", BillingCalculator.orderTierAdjustment(100, rule, List.of("7")).get("amount"));
        money("0", BillingCalculator.orderTierAdjustment(0, map("tiers", List.of(map("from", 1, "rate", 2)), "minimumCharge", 10), List.of()).get("amount"));
        assertThrows(IllegalArgumentException.class, () -> BillingCalculator.tierTotal(-1, List.of(map("from", 1, "rate", 1)), false, 0));
        assertThrows(IllegalArgumentException.class, () -> BillingCalculator.tierTotal(BillingCalculator.MAX_SAFE_QUANTITY + 1, List.of(map("from", 1, "rate", 1)), false, 0));
    }

    @Test
    void periodCalendarHandlesLeapDaysAndCrossYearMonday() {
        assertEquals(Map.of("from", "2024-02-01", "to", "2024-02-29"), BillingPeriods.period("2024-02-29", "Monthly"));
        assertEquals(Map.of("from", "2025-12-29", "to", "2026-01-04"), BillingPeriods.period("2026-01-01", "Weekly"));
        assertEquals(Map.of("from", "2026-01-04", "to", "2026-01-04"), BillingPeriods.period("2026-01-04", "Daily"));
        assertThrows(IllegalArgumentException.class, () -> BillingPeriods.period("2026-02-29", "Monthly"));
        assertThrows(IllegalArgumentException.class, () -> BillingPeriods.period("2026-1-4", "Daily"));
    }

    @Test
    void savedPhysicalBundleSnapshotSurvivesComponentChanges() {
        List<Map<String, Object>> products = List.of(
            map("id", "bundle", "bundle", true, "components", List.of(map("product", "part", "quantity", 99))),
            map("id", "part", "weight", "0.125"));
        Map<String, Object> historical = map("lines", List.of(map("product", "bundle", "quantity", 5)), "fulfillmentLines", List.of(map("product", "part", "quantity", 3)));
        money("0.375", ShipmentWeightCalculator.goodsWeight(historical, products));
        money("61.875", ShipmentWeightCalculator.goodsWeight(map("lines", historical.get("lines")), products));
    }

    @Test
    void physicalWeightStaysAtInclusiveCourierBoundary() {
        List<Map<String, Object>> products = List.of(map("id", "a", "weight", "0.1"), map("id", "b", "weight", "0.2"));
        Map<String, Object> order = map("lines", List.of(map("product", "a", "quantity", 1), map("product", "b", "quantity", 1)));
        BigDecimal weight = ShipmentWeightCalculator.goodsWeight(order, products);
        money("0.3", weight);
        money("1", BillingCalculator.courierQuote(map("rule", map("parcelCost", 1, "maxWeight", "0.3"), "weight", weight)).get("amount"));
    }

    @Test
    void invalidPhysicalWeightAndVirtualComponentsCannotBeShipped() {
        Map<String, Object> order = map("lines", List.of(map("product", "bundle", "quantity", 1)));
        assertThrows(IllegalArgumentException.class, () -> ShipmentWeightCalculator.goodsWeight(order, List.of(map("id", "bundle", "bundle", true))));
        assertThrows(IllegalArgumentException.class, () -> ShipmentWeightCalculator.goodsWeight(order, List.of(map("id", "bundle", "bundle", true, "components", List.of(map("product", "missing", "quantity", 1))))));
        for (Object weight : List.of("", "NaN", "Infinity", "-1")) assertThrows(IllegalArgumentException.class, () -> ShipmentWeightCalculator.goodsWeight(order, List.of(map("id", "bundle", "weight", weight))));
        assertThrows(IllegalArgumentException.class, () -> ShipmentWeightCalculator.goodsWeight(order, List.of(map("id", "bundle"))));
        assertThrows(IllegalArgumentException.class, () -> ShipmentWeightCalculator.goodsWeight(order, List.of(map("id", "bundle", "dropship", true))));
        assertThrows(IllegalArgumentException.class, () -> ShipmentWeightCalculator.goodsWeight(map("fulfillmentLines", List.of(map("product", "bundle", "quantity", 1))), List.of(map("id", "bundle", "bundle", true))));
        assertThrows(IllegalArgumentException.class, () -> ShipmentWeightCalculator.goodsWeight(map("lines", List.of(map("product", "bundle", "quantity", "1.1"))), List.of(map("id", "bundle", "weight", 1))));
    }

    @Test
    void verifiedPackingAndMaterialsUsePhysicalGoodsAndZeroPackingWeight() {
        Map<String, Object> order = map("id", "order", "lines", List.of(map("product", "a", "quantity", 2)));
        List<Map<String, Object>> products = List.of(map("id", "a", "weight", "0.1"));
        List<Map<String, Object>> materials = List.of(map("additionalWeight", true, "unitWeight", "0.05", "netQuantity", 2), map("additionalWeight", false, "unitWeight", 99, "netQuantity", 1));
        money("0.3", ShipmentWeightCalculator.shipmentWeight(order, products, null, materials));
        money("0", ShipmentWeightCalculator.shipmentWeight(order, products, BigDecimal.ZERO, materials));
        money("0.2", ShipmentWeightCalculator.shipmentWeight(map("lines", order.get("lines")), products, new BigDecimal("9"), materials));
    }
}
