package com.ruoyi.warehouse.billing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Physical goods weight from caller-supplied historical fulfillment snapshots. */
public final class ShipmentWeightCalculator {
    private ShipmentWeightCalculator() { }

    public static BigDecimal goodsWeight(Map<String, Object> order, List<Map<String, Object>> products) {
        List<Map<String, Object>> lines = BillingCalculator.rows(order.get("fulfillmentLines"));
        if (lines.isEmpty()) {
            List<Map<String, Object>> original = BillingCalculator.rows(order.get("lines"));
            if (original.isEmpty()) throw new IllegalArgumentException("Shipment has no warehouse product lines");
            lines = new ArrayList<>();
            for (Map<String, Object> line : original) {
                Map<String, Object> product = product(products, line.get("product"));
                long quantity = BillingCalculator.safeQuantity(BillingCalculator.number(line.get("quantity")), true);
                if (BillingCalculator.truthy(product.get("dropship"))) continue;
                if (BillingCalculator.truthy(product.get("bundle"))) {
                    List<Map<String, Object>> components = BillingCalculator.rows(product.get("components"));
                    if (components.isEmpty()) throw new IllegalArgumentException("Bundle components are required to calculate shipment weight");
                    for (Map<String, Object> component : components) lines.add(BillingCalculator.map("product", component.get("product"), "quantity", BigDecimal.valueOf(quantity).multiply(BillingCalculator.number(component.get("quantity")))));
                } else lines.add(BillingCalculator.map("product", product.get("id"), "quantity", quantity));
            }
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("Shipment has no warehouse product lines");
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> line : lines) {
            long quantity = BillingCalculator.safeQuantity(BillingCalculator.number(line.get("quantity")), true);
            Map<String, Object> product = product(products, line.get("product"));
            if (BillingCalculator.truthy(product.get("bundle")) || BillingCalculator.truthy(product.get("dropship"))) throw new IllegalArgumentException("Shipment weight requires expanded physical product lines");
            Object value = product.get("weight");
            if (!BillingCalculator.present(value)) throw new IllegalArgumentException("Physical product weight is required to calculate shipment weight");
            BigDecimal weight;
            try { weight = BillingCalculator.decimal(value); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Physical product weight is required to calculate shipment weight", error); }
            if (weight.signum() < 0) throw new IllegalArgumentException("Physical product weight is required to calculate shipment weight");
            total = total.add(weight.multiply(BigDecimal.valueOf(quantity)));
        }
        return total;
    }

    /**
     * The service must supply verified packing/material evidence. A confirmed
     * packing weight overrides goods + materials only for an identified order.
     * This calculator does not manufacture or validate those persisted proofs.
     */
    public static BigDecimal shipmentWeight(Map<String, Object> order, List<Map<String, Object>> products,
                                            BigDecimal confirmedPackingWeight, List<Map<String, Object>> materialUses) {
        BigDecimal goods = goodsWeight(order, products);
        if (!BillingCalculator.truthy(order.get("id"))) return goods;
        if (confirmedPackingWeight != null) return confirmedPackingWeight;
        BigDecimal total = goods;
        for (Map<String, Object> use : materialUses == null ? List.<Map<String, Object>>of() : materialUses) {
            if (BillingCalculator.truthy(use.get("additionalWeight"))) total = total.add(BillingCalculator.decimal(use.get("unitWeight")).multiply(BigDecimal.valueOf(BillingCalculator.safeQuantity(BillingCalculator.number(use.get("netQuantity")), false))));
        }
        return total;
    }

    private static Map<String, Object> product(List<Map<String, Object>> products, Object id) {
        Map<String, Object> found = BillingCalculator.find(products, "id", id);
        if (found == null) throw new IllegalArgumentException("Record not found");
        return found;
    }
}
