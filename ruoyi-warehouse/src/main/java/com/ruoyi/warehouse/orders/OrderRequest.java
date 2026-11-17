package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class OrderRequest {
    static final Set<String> ACTIONS = Set.of("allocate", "pick", "cancel", "despatch", "hold", "release");
    static final List<String> TEXT = List.of("client", "warehouse", "reference", "name", "companyName", "address1", "address2", "address3", "town", "county", "postcode", "country", "email", "phone", "notes");
    static final Set<String> REQUIRED = Set.of("client", "warehouse", "reference", "name", "address1", "town", "postcode", "country");
    private OrderRequest() { }

    static Map<String, Object> data(Map<String, Object> input, boolean update) {
        if (input == null || WarehouseJson.write(input).length() > 131072) throw WarehouseException.invalid("Order input is missing or too large");
        Set<String> allowed = new HashSet<>(TEXT); allowed.addAll(Set.of("lines", "orderValue", "requestId", "channelId")); if (update) allowed.add("_version");
        keys(input, allowed); id(input, "requestId"); if (update) WarehouseJson.integer(input.get("_version"), "_version", 1, Long.MAX_VALUE);
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : TEXT) {
            int length = "notes".equals(key) ? 5000 : "reference".equals(key) ? 191 : Set.of("client", "warehouse").contains(key) ? 64 : 255;
            result.put(key, text(input, key, length, REQUIRED.contains(key)));
        }
        id(result, "client"); id(result, "warehouse");
        String email = WarehouseJson.text(result, "email"); if (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw WarehouseException.invalid("Invalid recipient email");
        if (!(input.get("lines") instanceof List<?> source) || source.isEmpty() || source.size() > 200) throw WarehouseException.invalid("An order needs 1 to 200 product lines");
        List<Map<String, Object>> lines = new ArrayList<>(); Set<String> seen = new HashSet<>(); long total = 0;
        for (Object value : source) {
            if (!(value instanceof Map<?, ?> raw)) throw WarehouseException.invalid("Invalid order line");
            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) raw;
            keys(row, Set.of("product", "quantity")); String product = id(row, "product");
            if (!seen.add(product)) throw WarehouseException.invalid("Combine duplicate product lines before saving / 请合并重复商品行");
            long quantity = WarehouseJson.integer(row.get("quantity"), "quantity", 1, 10_000_000); total += quantity;
            if (total > 10_000_000) throw WarehouseException.invalid("Order quantity exceeds limit");
            lines.add(new LinkedHashMap<>(Map.of("product", product, "quantity", quantity)));
        }
        result.put("lines", lines);
        if (!WarehouseJson.empty(input.get("orderValue"))) {
            try { BigDecimal price = new BigDecimal(input.get("orderValue").toString()); if (price.signum() < 0 || price.precision() > 24 || (long) price.precision() - price.scale() > 24 || price.scale() > 9) throw new NumberFormatException(); result.put("orderValue", price); }
            catch (Exception error) { throw WarehouseException.invalid("Invalid order value"); }
        }
        if(input.containsKey("channelId"))result.put("channelId",text(input,"channelId",64,false));
        result.put("channel", "Manual"); result.put("source", "MANUAL"); result.put("workflow", "JAVA_MANUAL_V1");
        return result;
    }

    static Map<String, Object> action(Map<String, Object> input, String order) {
        keys(input, Set.of("requestId", "action", "tracking")); id(input, "requestId");
        String action = text(input, "action", 16, true); if (!ACTIONS.contains(action)) throw WarehouseException.invalid("Unsupported order action");
        String tracking = text(input, "tracking", 191, false); if (!"despatch".equals(action) && !tracking.isEmpty()) throw WarehouseException.invalid("Tracking is only accepted with manual despatch");
        return new LinkedHashMap<>(Map.of("order", order, "action", action, "tracking", tracking));
    }

    static void keys(Map<String, Object> input, Set<String> allowed) { if (input == null) throw WarehouseException.invalid("Request is required"); for (String key : input.keySet()) if (!allowed.contains(key)) throw WarehouseException.invalid("Unknown or protected order field: " + key); }
    static String id(Map<String, Object> input, String key) { String value = text(input, key, 64, true); if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw WarehouseException.invalid(key + " is not a valid identifier"); return value; }
    static String text(Map<String, Object> input, String key, int max, boolean required) { Object raw = input.get(key); if (raw != null && !(raw instanceof String)) throw WarehouseException.invalid(key + " must be text"); String value = raw == null ? "" : ((String) raw).strip(); if (value.length() > max || value.indexOf('\0') >= 0 || required && value.isEmpty()) throw WarehouseException.invalid(key + " is missing or exceeds text limits"); return value; }
    static String hash(Object value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(canonical(value)).getBytes(StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> out = new TreeMap<>(); map.forEach((key, item) -> out.put(String.valueOf(key), canonical(item))); return out; }
        if (value instanceof List<?> list) return list.stream().map(OrderRequest::canonical).toList();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros(); return value;
    }
}
