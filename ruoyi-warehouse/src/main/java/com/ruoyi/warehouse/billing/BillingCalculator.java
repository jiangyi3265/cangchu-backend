package com.ruoyi.warehouse.billing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure warehouse pricing. Inputs are validated domain snapshots supplied by the
 * application service; this class has no database, network, or JavaScript engine.
 * Monetary arithmetic retains nine fractional digits, truncating multiplication
 * toward zero, and rounds only displayed line/quote amounts to cents (half even).
 * A line's raw field remains an integer number of 10^-9 currency units for audit.
 */
public final class BillingCalculator {
    private static final int SCALE = 9;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;
    public static final long MAX_SAFE_QUANTITY = 9_007_199_254_740_991L;

    private BillingCalculator() { }

    /** Actual decimal amount, not an integer number of nanounits. */
    public static BigDecimal decimal(Object value) {
        if (value == null || "".equals(value)) return ZERO.setScale(SCALE);
        String text = String.valueOf(value);
        // Java uses exponent notation for more ordinary decimal magnitudes than
        // JavaScript. Keep numeric values in its ordinary [1e-6, 1e21) range plain.
        if (value instanceof Number && !(value instanceof BigInteger)) {
            try {
                BigDecimal numeric = new BigDecimal(text);
                if (numeric.abs().compareTo(new BigDecimal("0.000001")) >= 0 && numeric.abs().compareTo(new BigDecimal("1e21")) < 0) text = numeric.toPlainString();
            } catch (NumberFormatException ignored) { /* The validation below reports it. */ }
        }
        if (!text.matches("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")) {
            throw new IllegalArgumentException("Invalid decimal amount: " + text);
        }
        try {
            if (text.matches(".*[eE].*")) {
                // Compatibility with the old scientific-notation input parser's
                // Number(value).toFixed(9), including its binary midpoint choice.
                // Only this input conversion uses double; all pricing stays decimal.
                double expanded = Double.parseDouble(text);
                if (!Double.isFinite(expanded) || Math.abs(expanded) >= 1e21) {
                    String invalid = Double.toString(expanded).replace(".0E", "E").replace("E-", "e-").replace("E", "e+");
                    throw new IllegalArgumentException("Invalid decimal amount: " + invalid);
                }
                return new BigDecimal(expanded).setScale(SCALE, RoundingMode.HALF_UP);
            }
            return new BigDecimal(text).setScale(SCALE, RoundingMode.DOWN);
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Invalid decimal amount: " + text, error);
        }
    }

    public static BigDecimal moneyRound(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal fromRaw(String raw) {
        return new BigDecimal(new BigInteger(raw), SCALE);
    }

    public static String toRaw(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.DOWN).unscaledValue().toString();
    }

    private static BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return left.multiply(right).setScale(SCALE, RoundingMode.DOWN);
    }

    public static BigDecimal productVolume(Map<String, Object> product, boolean carton) {
        BigDecimal explicit = number(product.get("volume"));
        if (!carton && explicit.signum() > 0) return explicit;
        BigDecimal height = number(product.get(carton ? "cartonHeight" : "height"));
        BigDecimal length = number(product.get(carton ? "cartonLength" : "length"));
        BigDecimal depth = number(product.get(carton ? "cartonDepth" : "depth"));
        if (height.signum() == 0 || length.signum() == 0 || depth.signum() == 0) {
            throw new IllegalArgumentException("Dimensions are required for " + text(product, "sku") + (carton ? " carton" : ""));
        }
        return multiply(multiply(decimal(height), decimal(length)), decimal(depth))
            .divide(BigDecimal.valueOf(1_000_000), SCALE, RoundingMode.DOWN);
    }

    public static Map<String, Object> tierTotal(long quantity, List<Map<String, Object>> tiers,
                                                boolean split, Object minimum) {
        if (quantity < 0 || quantity > MAX_SAFE_QUANTITY) {
            throw new IllegalArgumentException("Tier quantity must be a non-negative integer");
        }
        List<Map<String, Object>> sorted = new ArrayList<>(tiers == null ? List.of() : tiers);
        sorted.sort(Comparator.comparing(tier -> number(tier.get("from"))));
        if (sorted.isEmpty() || number(sorted.get(0).get("from")).compareTo(ONE) != 0) {
            throw new IllegalArgumentException("First tier must start at 1");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        if (quantity == 0) return result(lines, Map.of());
        BigDecimal total = ZERO, count = BigDecimal.valueOf(quantity);
        if (split) {
            for (int i = 0; i < sorted.size(); i++) {
                Map<String, Object> tier = sorted.get(i);
                boolean hasNext = i + 1 < sorted.size();
                BigDecimal next = hasNext ? number(sorted.get(i + 1).get("from")).subtract(ONE) : count;
                BigDecimal units = count.min(next).subtract(number(tier.get("from"))).add(ONE).max(ZERO);
                if (units.signum() != 0) {
                    Map<String, Object> line = item("Tier " + text(tier, "from") + "–" + (hasNext ? numericText(next) : "∞"), units, tier.get("rate"), "tier");
                    lines.add(line); total = total.add(raw(line));
                }
            }
        } else {
            Map<String, Object> selected = sorted.get(0);
            for (Map<String, Object> tier : sorted) if (number(tier.get("from")).compareTo(count) <= 0) selected = tier;
            Map<String, Object> line = item("Tier " + text(selected, "from") + " and above", count, selected.get("rate"), "tier");
            lines.add(line); total = raw(line);
        }
        BigDecimal floor = decimal(minimum);
        if (total.compareTo(floor) < 0) lines.add(item("Minimum charge adjustment", ONE, floor.subtract(total), "minimum"));
        return result(lines, Map.of());
    }

    public static Map<String, Object> pickingQuote(Map<String, Object> context) {
        Map<String, Object> client = object(context.get("client")), order = object(context.get("order"));
        List<Map<String, Object>> products = rows(context.get("products"));
        List<Map<String, Object>> overrides = rows(context.get("overrides"));
        List<Unit> lines = new ArrayList<>();
        for (Map<String, Object> line : fulfillmentLines(order)) {
            Map<String, Object> product = find(products, "id", line.get("product"));
            if (product == null) throw new IllegalArgumentException("Picking product not found");
            lines.add(new Unit(product, number(line.get("quantity"))));
        }
        BigDecimal quantity = lines.stream().map(Unit::quantity).reduce(ZERO, BigDecimal::add);
        String calculation = textOr(client, "pickingCalculationType", "PerItem");
        for (Map<String, Object> tier : rows(context.get("channelTiers"))) {
            if (enabled(tier) && same(tier.get("client"), client.get("id")) && same(tier.get("channel"), order.get("channel")) && "Units".equals(tier.get("basis"))) {
                Map<String, Object> quoted = tierTotal(safeQuantity(quantity, false), rows(tier.get("tiers")), truthy(tier.get("splitTiers")), tier.get("minimumCharge"));
                quoted.put("calculation", "ChannelTier"); quoted.put("rule", tier.get("id")); return quoted;
            }
        }
        List<Map<String, Object>> output = new ArrayList<>();
        Rates rates = new Rates(client, order, overrides);
        switch (calculation) {
            case "PerItem" -> {
                List<Unit> units = new ArrayList<>(lines);
                if (truthy(client.get("palletPicking"))) units = splitUnits(units, "unitsPerPallet", "firstPallet", "additionalPallet", rates, output);
                if (truthy(client.get("cartonPicking"))) units = splitUnits(units, "unitsPerCarton", "firstCarton", "additionalCarton", rates, output);
                perUnit(units, "firstPick", "additionalPick", or(client.get("includedItems"), 1), rates, output);
            }
            case "PerOrderLine" -> perUnit(lines.stream().map(line -> new Unit(line.product(), ONE)).toList(), "firstPick", "additionalPick", or(client.get("includedItems"), 1), rates, output);
            case "PerOrderPerSkuType", "PerOrderPerSkuPerType", "PerOrderPerSkuPerUnit" -> {
                output.add(item("Order pick", ONE, client.get("firstPick"), "client"));
                output.add(item("Per unit", quantity, client.get("additionalPick"), "client"));
                output.add(item("Per SKU", lines.size(), client.get("perSkuPick"), "client"));
            }
            case "PercentageOfOrderValue" -> {
                BigDecimal value = present(order.get("orderValue")) ? number(order.get("orderValue")) : lines.stream().map(line -> line.quantity().multiply(number(line.product().get("price")))).reduce(ZERO, BigDecimal::add);
                BigDecimal percent = number(client.get("percentagePickingCost"));
                output.add(item("Order value × " + numericText(percent) + "%", value, percent.movePointLeft(2), "client:percentage-points"));
            }
            case "PerCubicMeter" -> {
                for (Unit line : lines) output.add(item("Volume · " + text(line.product(), "sku"), line.quantity().multiply(productVolume(line.product(), false)), client.get("cubicPickingCost"), "client"));
            }
            default -> throw new IllegalArgumentException("Unsupported picking calculation " + calculation);
        }
        return result(output, map("calculation", calculation, "quantity", quantity, "skuCount", lines.size()));
    }

    private static List<Unit> splitUnits(List<Unit> input, String capacityKey, String first, String additional, Rates rates, List<Map<String, Object>> output) {
        List<Unit> full = new ArrayList<>(), remainder = new ArrayList<>();
        for (Unit line : input) {
            BigDecimal capacity = number(line.product().get(capacityKey));
            BigDecimal count = capacity.signum() != 0 ? line.quantity().divide(capacity, 0, RoundingMode.FLOOR) : ZERO;
            if (count.signum() != 0) full.add(new Unit(line.product(), count));
            remainder.add(new Unit(line.product(), line.quantity().subtract(count.multiply(capacity))));
        }
        perUnit(full, first, additional, ONE, rates, output); return remainder;
    }

    private static void perUnit(List<Unit> input, String firstKey, String additionalKey, Object included, Rates rates, List<Map<String, Object>> output) {
        List<Unit> sorted = new ArrayList<>(input.stream().filter(unit -> unit.quantity().signum() > 0).toList());
        sorted.sort((left, right) -> number(rates.get(right.product(), firstKey).value()).compareTo(number(rates.get(left.product(), firstKey).value())));
        if (sorted.isEmpty()) return;
        BigDecimal skip = number(included).max(ONE);
        Unit first = sorted.get(0); Rate rate = rates.get(first.product(), firstKey);
        output.add(item(firstKey + " · " + text(first.product(), "sku"), ONE, rate.value(), rate.source()));
        for (Unit unit : sorted) {
            BigDecimal free = skip.min(unit.quantity()); skip = skip.subtract(free);
            BigDecimal chargeable = unit.quantity().subtract(free);
            if (chargeable.signum() != 0 && !truthy(unit.product().get("noAdditionalPick"))) {
                Rate extra = rates.get(unit.product(), additionalKey);
                output.add(item(additionalKey + " · " + text(unit.product(), "sku"), chargeable, extra.value(), extra.source()));
            }
        }
    }

    public static Map<String, Object> orderQuote(Map<String, Object> context) {
        Map<String, Object> client = object(context.get("client")), order = object(context.get("order"));
        if (truthy(order.get("splitParent")) || truthy(order.get("billingExcluded"))) return result(List.of(), map("calculation", "Included in original order", "billingParent", or(order.get("splitParent"), null)));
        Map<String, Object> quote = truthy(context.get("excludePicking")) ? map("lines", List.of(), "calculation", "Order extras") : pickingQuote(context);
        List<Map<String, Object>> output = new ArrayList<>(rows(quote.get("lines")));
        List<Map<String, Object>> products = rows(context.get("products")), packaging = new ArrayList<>();
        for (Map<String, Object> line : fulfillmentLines(order)) {
            Map<String, Object> product = find(products, "id", line.get("product"));
            if (product == null) throw new IllegalArgumentException("Picking product not found");
            if (present(product.get("packagingFee")) && number(product.get("packagingFee")).signum() > 0) packaging.add(product);
        }
        List<Map<String, Object>> rework = rows(context.get("reworkComponents"));
        BigDecimal reworkTotal = rework.stream().map(BillingCalculator::raw).reduce(ZERO, BigDecimal::add);
        if (number(order.get("reworkAmount")).signum() > 0 || reworkTotal.signum() > 0) {
            if (number(order.get("reworkAmount")).signum() > 0) output.add(item("Rework", ONE, order.get("reworkAmount"), "order"));
            rework.forEach(line -> output.add(new LinkedHashMap<>(line)));
        } else if (!packaging.isEmpty()) {
            String mode = textOr(client, "packagingAggregation", "Highest");
            BigDecimal price = "Sum".equals(mode) ? packaging.stream().map(p -> number(p.get("packagingFee"))).reduce(ZERO, BigDecimal::add) : packaging.stream().map(p -> number(p.get("packagingFee"))).max(BigDecimal::compareTo).orElse(ZERO);
            output.add(item("Product packaging (" + mode + ")", ONE, price, "product"));
        } else output.add(item("Default packaging", ONE, client.get("packagingFee"), "client"));
        output.add(item("Order administration", ONE, client.get("orderAdminFee"), "client"));
        return result(output, map("calculation", quote.get("calculation")));
    }

    public static Map<String, Object> goodsInQuote(Map<String, Object> context) {
        Map<String, Object> client = object(context.get("client")), asn = object(context.get("asn"));
        List<Map<String, Object>> products = rows(context.get("products")), output = new ArrayList<>();
        Set<Object> billed = new LinkedHashSet<>(values(asn.get("billedSkus")));
        for (Map<String, Object> line : rows(context.get("received"))) {
            Map<String, Object> product = find(products, "id", line.get("product"));
            if (product == null) throw new IllegalArgumentException("Receiving product not found");
            if (truthy(product.get("noGoodsIn"))) continue;
            Object rate = present(product.get("goodsInRate")) ? product.get("goodsInRate") : client.get("goodsInRate");
            output.add(item("Goods in units · " + text(product, "sku"), line.get("quantity"), rate, "asn:unit"));
            if (!billed.contains(product.get("id"))) {
                rate = present(product.get("goodsInSku")) ? product.get("goodsInSku") : client.get("goodsInSku");
                output.add(item("Goods in SKU · " + text(product, "sku"), ONE, rate, "asn:sku")); billed.add(product.get("id"));
            }
        }
        boolean complete = truthy(context.get("complete"));
        if (complete && !truthy(asn.get("fixedChargesBilled"))) {
            String key = switch (text(asn, "deliveryType")) {
                case "FCL Container 20" -> "goodsIn20";
                case "FCL Container 40" -> "goodsIn40";
                case "FCL Container 40 HC" -> "goodsIn40HC";
                case "FCL Container 45" -> "goodsIn45";
                case "FCL Container 45 HC" -> "goodsIn45HC";
                case "LCL Pallet" -> "goodsInPallet";
                case "LCL Boxes" -> "goodsInCarton";
                default -> "";
            };
            output.add(item("Goods in " + text(asn, "deliveryType"), or(asn.get("deliveryQuantity"), 1), client.get(key), "asn:delivery"));
            output.add(item("ASN administration", ONE, client.get("asnAdmin"), "asn:admin"));
            output.add(item("ASN labour hours", or(asn.get("hours"), 0), client.get("asnHour"), "asn:hours"));
        }
        return result(output, map("billedSkus", new ArrayList<>(billed), "fixedChargesBilled", complete || truthy(asn.get("fixedChargesBilled"))));
    }

    public static Map<String, Object> storageQuote(Map<String, Object> context) {
        Map<String, Object> client = object(context.get("client"));
        List<Map<String, Object>> stock = rows(context.get("stock")), products = rows(context.get("products"));
        String type = textOr(client, "storageType", "Volumetric");
        List<Map<String,Object>> unidentified=rows(context.get("unidentifiedStock")).stream().filter(row->same(row.get("client"),client.get("id"))&&number(row.get("onhand")).signum()>0).toList();
        if(!unidentified.isEmpty()&&!Set.of("LocationTypeUsage","HighestPointLocationTypeUsage","StockManagementFee").contains(type))throw new IllegalArgumentException("Unidentified historical goods require product and billing review");
        String frequency = context.containsKey("frequency") ? String.valueOf(context.get("frequency")) : textOr(client, "storageFrequency", "Daily");
        List<Map<String, Object>> own = stock.stream().filter(row -> ownedPositive(row, products, client.get("id"))).toList();
        Map<Object, BigDecimal> totals = new LinkedHashMap<>();
        for (Map<String, Object> row : own) totals.merge(row.get("product"), number(row.get("onhand")), BigDecimal::add);
        List<Map<String, Object>> output = new ArrayList<>();
        if ("LocationTypeUsage".equals(type) || "HighestPointLocationTypeUsage".equals(type)) {
            List<Map<String, Object>> locations = rows(context.get("locations"));
            Map<String, Integer> groups = countLocationsIncludingUnidentified(own, products, locations, unidentified, client.get("id"));
            if ("HighestPointLocationTypeUsage".equals(type)) {
                if(context.get("locationPeaks") instanceof Map<?,?> peaks){
                    groups=new LinkedHashMap<>();for(var entry:peaks.entrySet()){long count=safeQuantity(number(entry.getValue()),false);if(count>Integer.MAX_VALUE)throw new IllegalArgumentException("Location peak exceeds supported range");groups.put(String.valueOf(entry.getKey()),(int)count);}
                }else{
                List<Map<String, Object>> history = rows(context.get("history"));
                if (history.isEmpty()) throw new IllegalArgumentException("Historical storage snapshots are required; current stock cannot represent a historical peak");
                groups = new LinkedHashMap<>();
                for (Map<String, Object> snapshot : history) {
                    for (Map.Entry<String, Integer> entry : countLocationsIncludingUnidentified(rows(snapshot.get("stock")), products, locations, rows(snapshot.get("unidentifiedStock")), client.get("id")).entrySet()) groups.merge(entry.getKey(), entry.getValue(), Math::max);
                }
                }
            }
            for (Map.Entry<String, Integer> entry : groups.entrySet()) {
                List<Map<String, Object>> prices = rows(context.get("locationPrices")).stream().filter(rule -> entry.getKey().equals(rule.get("locationType")) && frequency.equals(rule.get("frequency")) && enabled(rule)).toList();
                Map<String, Object> price = prices.stream().filter(rule -> same(rule.get("client"), client.get("id"))).findFirst().orElse(null);
                if (price == null) price = prices.stream().filter(rule -> !truthy(rule.get("client"))).findFirst().orElse(null);
                if (price == null) throw new IllegalArgumentException("Missing " + frequency + " storage price for location type " + entry.getKey());
                output.add(item("Storage locations · " + entry.getKey(), entry.getValue(), price.get("cost"), "location-price:" + text(price, "id")));
            }
        } else if ("StockManagementFee".equals(type)) output.add(item("Stock management fee", ONE, client.get("stockManagementFee"), "client"));
        else {
            for (Map.Entry<Object, BigDecimal> entry : totals.entrySet()) {
                Map<String, Object> product = find(products, "id", entry.getKey());
                BigDecimal quantity = entry.getValue(), chargedQuantity = quantity;
                Object rate;
                switch (type) {
                    case "StorageUnits" -> rate = nonzeroOr(number(product.get("unitStoragePrice")), number(client.get("unitStorageFee")));
                    case "Volumetric" -> { chargedQuantity = quantity.multiply(productVolume(product, false)); rate = client.get("storageRate"); }
                    case "VolumetricCarton" -> {
                        BigDecimal capacity = number(product.get("unitsPerCarton"));
                        if (capacity.signum() == 0) throw new IllegalArgumentException("Carton size is required for " + text(product, "sku"));
                        chargedQuantity = quantity.divide(capacity, 0, RoundingMode.CEILING).multiply(productVolume(product, true)); rate = client.get("storageRate");
                    }
                    case "PerPalletUnit" -> {
                        String sizesValue = String.valueOf(or(product.get("palletSizes"), or(product.get("unitsPerPallet"), "")));
                        List<BigDecimal> sizes = new ArrayList<>();
                        for (String value : sizesValue.split("[,; ]+")) { BigDecimal size = number(value); if (size.signum() > 0) sizes.add(size); }
                        sizes.sort(Comparator.reverseOrder());
                        if (sizes.isEmpty()) throw new IllegalArgumentException("Pallet size is required for " + text(product, "sku"));
                        BigDecimal remaining = quantity; chargedQuantity = ZERO;
                        for (BigDecimal size : sizes) { BigDecimal full = remaining.divide(size, 0, RoundingMode.FLOOR); chargedQuantity = chargedQuantity.add(full); remaining = remaining.subtract(full.multiply(size)); }
                        if (remaining.signum() != 0) chargedQuantity = chargedQuantity.add(ONE);
                        rate = nonzeroOr(number(product.get("unitStoragePrice")), number(client.get("unitStorageFee")));
                    }
                    default -> throw new IllegalArgumentException("Unsupported storage type " + type);
                }
                output.add(item(type + " · " + text(product, "sku"), chargedQuantity, rate, "storage:" + type));
            }
        }
        Map<String, Object> metadata = map("type", type, "frequency", frequency);
        for (String key : List.of("from", "to")) if (context.containsKey(key)) metadata.put(key, context.get(key));
        return result(output, metadata);
    }

    public static Map<String, Object> courierQuote(Map<String, Object> context) {
        if (!(context.get("rule") instanceof Map<?, ?>)) throw new IllegalArgumentException("No courier price matches this shipment");
        Map<String, Object> rule = object(context.get("rule"));
        BigDecimal weight = number(context.get("weight")), parcels = context.containsKey("parcels") ? number(context.get("parcels")) : ONE;
        BigDecimal maximum = number(rule.get("maxWeight"));
        if (weight.compareTo(number(rule.get("minWeight"))) < 0 || maximum.signum() > 0 && weight.compareTo(maximum) > 0) throw new IllegalArgumentException("Shipment weight is outside the courier price range");
        BigDecimal cost;
        if (truthy(rule.get("useAdditionalAsPerKgRate"))) cost = multiply(decimal(parcels), decimal(rule.get("parcelCost"))).add(multiply(decimal(weight), decimal(rule.get("additionalParcelCost"))));
        else cost = decimal(rule.get("parcelCost")).add(multiply(decimal(parcels.subtract(ONE).max(ZERO)), decimal(rule.get("additionalParcelCost")))).add(multiply(decimal(weight.subtract(number(rule.get("includedWeight"))).max(ZERO)), decimal(rule.get("perKgRate"))));
        BigDecimal percent = number(rule.get("fuelPercent")), fuel = multiply(cost, decimal(percent.movePointLeft(2)));
        return result(List.of(rawItem("Courier charge", cost, "courier-price:" + text(rule, "id")), rawItem("Fuel surcharge " + numericText(percent) + "%", fuel, "courier-price:" + text(rule, "id"))), Map.of());
    }

    /** Per-period recurring fee: a provided zero quantity is not the default one. */
    public static BigDecimal recurringAmount(Map<String, Object> rule) {
        Object quantity = present(rule.get("quantity")) ? rule.get("quantity") : ONE;
        return moneyRound(multiply(decimal(rule.get("amount")), decimal(quantity)));
    }

    /** Count commercial orders, not units. The application chooses the eligible order set. */
    public static Map<String, Object> orderTierAdjustment(long orderCount, Map<String, Object> rule, List<?> previousAmounts) {
        Map<String, Object> cumulative = tierTotal(orderCount, rows(rule.get("tiers")), truthy(rule.get("splitTiers")), rule.get("minimumCharge"));
        BigDecimal previous = previousAmounts.stream().map(BillingCalculator::decimal).reduce(ZERO, BigDecimal::add);
        return map("orderCount", orderCount, "amount", moneyRound(decimal(cumulative.get("amount")).subtract(previous)), "previousCharge", moneyRound(previous), "cumulativeAmount", cumulative.get("amount"), "lines", cumulative.get("lines"));
    }

    private static Map<String, Integer> countLocations(List<Map<String, Object>> stock, List<Map<String, Object>> products, List<Map<String, Object>> locations, Object client) {
        return countLocationsIncludingUnidentified(stock,products,locations,List.of(),client);
    }
    private static Map<String, Integer> countLocationsIncludingUnidentified(List<Map<String, Object>> stock, List<Map<String, Object>> products, List<Map<String, Object>> locations,List<Map<String,Object>> unidentified,Object client) {
        Set<Object> occupied = new LinkedHashSet<>();
        for (Map<String, Object> row : stock) if (ownedPositive(row, products, client)) occupied.add(row.get("location"));
        for(Map<String,Object> row:unidentified)if(same(row.get("client"),client)&&number(row.get("onhand")).signum()>0){if(find(locations,"id",row.get("location"))==null)throw new IllegalArgumentException("Unidentified custody location is missing");occupied.add(row.get("location"));}
        Map<String, Integer> groups = new LinkedHashMap<>();
        for (Object id : occupied) { Map<String, Object> location = find(locations, "id", id); if (location != null && !"Unassigned".equals(location.get("name"))) groups.merge(text(location, "type"), 1, Integer::sum); }
        return groups;
    }

    private static boolean ownedPositive(Map<String, Object> row, List<Map<String, Object>> products, Object client) {
        return number(row.get("onhand")).signum() > 0 && products.stream().anyMatch(product -> same(product.get("id"), row.get("product")) && same(product.get("client"), client));
    }

    private record Unit(Map<String, Object> product, BigDecimal quantity) { }
    private record Rate(Object value, String source) { }
    private record Rates(Map<String, Object> client, Map<String, Object> order, List<Map<String, Object>> overrides) {
        Rate get(Map<String, Object> product, String key) {
            Map<String, Object> specific = null, general = null;
            for (Map<String, Object> rule : overrides) {
                if (!enabled(rule) || !same(rule.get("client"), client.get("id")) || !same(rule.get("warehouse"), order.get("warehouse"))) continue;
                if (specific == null && same(rule.get("product"), product.get("id"))) specific = rule;
                if (general == null && !truthy(rule.get("product"))) general = rule;
            }
            if (specific != null && present(specific.get(key))) return new Rate(specific.get(key), "warehouse:" + text(specific, "id"));
            if (general != null && present(general.get(key))) return new Rate(general.get(key), "warehouse:" + text(general, "id"));
            if (present(product.get(key))) return new Rate(product.get(key), "product:" + text(product, "id"));
            return new Rate(or(client.get(key), 0), "client:" + text(client, "id"));
        }
    }

    private static Map<String, Object> item(String description, Object quantity, Object rate, String source) {
        BigDecimal raw = multiply(decimal(quantity), decimal(rate));
        return map("description", description, "quantity", number(quantity), "rate", number(rate), "amount", moneyRound(raw), "raw", toRaw(raw), "source", source);
    }
    private static Map<String, Object> rawItem(String description, BigDecimal amount, String source) {
        return map("description", description, "quantity", ONE, "rate", amount, "amount", moneyRound(amount), "raw", toRaw(amount), "source", source);
    }
    private static Map<String, Object> result(List<Map<String, Object>> lines, Map<String, Object> metadata) {
        BigDecimal total = lines.stream().map(BillingCalculator::raw).reduce(ZERO, BigDecimal::add);
        Map<String, Object> result = map("lines", new ArrayList<>(lines.stream().filter(line -> raw(line).signum() != 0).toList()), "amount", moneyRound(total));
        result.putAll(metadata); return result;
    }
    private static BigDecimal raw(Map<String, Object> line) { return fromRaw(String.valueOf(line.get("raw"))); }
    private static BigDecimal nonzeroOr(BigDecimal value, BigDecimal fallback) { return value.signum() == 0 ? fallback : value; }
    static boolean present(Object value) { return value != null && !"".equals(value); }
    static boolean truthy(Object value) { return value != null && !Boolean.FALSE.equals(value) && !"".equals(value) && (!(value instanceof Number) || number(value).signum() != 0); }
    private static Object or(Object value, Object fallback) { return truthy(value) ? value : fallback; }
    private static boolean enabled(Map<String, Object> row) { return !Boolean.FALSE.equals(row.get("active")) && !"N".equals(row.get("active")); }
    private static boolean same(Object left, Object right) { return Objects.equals(left, right); }
    private static String text(Map<String, Object> row, String key) { return row.containsKey(key) ? row.get(key) == null ? "null" : row.get(key) instanceof BigDecimal d ? numericText(d) : String.valueOf(row.get(key)) : "undefined"; }
    private static String textOr(Map<String, Object> row, String key, String fallback) { return truthy(row.get(key)) ? text(row, key) : fallback; }
    private static String numericText(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    static BigDecimal number(Object value) {
        if (value == null || "".equals(value)) return ZERO;
        if (value instanceof Boolean flag) return flag ? ONE : ZERO;
        try { return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value).trim()); }
        catch (NumberFormatException ignored) { return ZERO; }
    }
    static long safeQuantity(BigDecimal value, boolean positive) {
        try { long quantity = value.longValueExact(); if (quantity < (positive ? 1 : 0) || quantity > MAX_SAFE_QUANTITY) throw new ArithmeticException(); return quantity; }
        catch (ArithmeticException error) { throw new IllegalArgumentException(positive ? "Shipment quantities must be positive whole numbers" : "Tier quantity must be a non-negative integer"); }
    }
    static Map<String, Object> find(List<Map<String, Object>> rows, String key, Object value) { return rows.stream().filter(row -> same(row.get(key), value)).findFirst().orElse(null); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) { if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("A calculation context object is required"); return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object value) { if (value == null) return List.of(); if (!(value instanceof List<?>)) throw new IllegalArgumentException("A calculation list is required"); return (List<Map<String, Object>>) value; }
    private static List<?> values(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static List<Map<String, Object>> fulfillmentLines(Map<String, Object> order) { return rows(truthy(order.get("fulfillmentLines")) ? order.get("fulfillmentLines") : order.get("lines")); }
    static Map<String, Object> map(Object... values) { Map<String, Object> out = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]); return out; }
}
