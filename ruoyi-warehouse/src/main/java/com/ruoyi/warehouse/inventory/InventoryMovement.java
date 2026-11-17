package com.ruoyi.warehouse.inventory;

import com.ruoyi.warehouse.support.WarehouseException;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Canonical manual movement request. Historical receipts cannot be backdated here. */
public record InventoryMovement(String requestId, String action, String product, String warehouse,
    String location, long quantity, String reason, String batch, String serial, String expiry,
    String toWarehouse, String toLocation) {
    private static final Set<String> FIELDS = Set.of("requestId", "action", "product", "warehouse", "location", "quantity", "reason", "batch", "serial", "expiry", "toWarehouse", "toLocation");

    public static InventoryMovement parse(Map<String, Object> input) {
        if (input == null) throw WarehouseException.invalid("Movement is required / 请填写库存操作");
        for (String field : input.keySet()) if (!FIELDS.contains(field)) throw WarehouseException.invalid("Unknown or protected movement field: " + field);
        String action = text(input, "action", 16, true);
        if (!Set.of("add", "remove", "transfer").contains(action)) throw WarehouseException.invalid("Unsupported inventory action / 库存操作无效");
        String destinationWarehouse = text(input, "toWarehouse", 64, false), destinationLocation = text(input, "toLocation", 64, false);
        String warehouse = identifier(input, "warehouse"), location = identifier(input, "location");
        if ("transfer".equals(action)) {
            destinationWarehouse = identifier(input, "toWarehouse"); destinationLocation = identifier(input, "toLocation");
            if (warehouse.equals(destinationWarehouse) && location.equals(destinationLocation)) throw WarehouseException.invalid("Transfer destination must differ from source / 移库目标不能与来源相同");
        } else if (!destinationWarehouse.isEmpty() || !destinationLocation.isEmpty()) throw WarehouseException.invalid("Destination fields are only valid for transfers");
        long quantity = WarehouseJson.integer(input.get("quantity"), "quantity", 1, 10_000_000);
        String batch = text(input, "batch", 191, false), serial = text(input, "serial", 191, false), expiry = text(input, "expiry", 10, false);
        if (!serial.isEmpty() && quantity != 1) throw WarehouseException.invalid("Serial-number movements require quantity 1 / 串号操作数量必须为 1");
        if (!expiry.isEmpty()) {
            try {
                if (!expiry.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
                LocalDate day = LocalDate.parse(expiry, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT));
                if (day.getYear() < 1) throw new IllegalArgumentException();
            } catch (Exception error) { throw WarehouseException.invalid("Expiry must be a valid UTC calendar date (YYYY-MM-DD) / 有效期日期无效"); }
        }
        return new InventoryMovement(identifier(input, "requestId"), action, identifier(input, "product"), warehouse, location, quantity, text(input, "reason", 500, true), batch, serial, expiry, destinationWarehouse, destinationLocation);
    }

    public void validateTracking(Map<String, Object> productRecord) {
        if (!"add".equals(action)) return; // Removal and transfer address one exact existing pool, including an untracked pool.
        if (WarehouseJson.enabled(productRecord.get("hasBatch")) && !"Optional".equals(productRecord.get("logBatchInbound")) && batch.isEmpty()) throw WarehouseException.invalid("Batch number is required for this product / 此商品必须填写批次");
        if (WarehouseJson.enabled(productRecord.get("hasSerial")) && !"Optional".equals(productRecord.get("logSerialInbound")) && serial.isEmpty()) throw WarehouseException.invalid("Serial number is required for this product / 此商品必须填写串号");
        if (WarehouseJson.enabled(productRecord.get("hasExpiry")) && !"Optional".equals(productRecord.get("logExpiryInbound")) && expiry.isEmpty()) throw WarehouseException.invalid("Expiry is required for this product / 此商品必须填写有效期");
    }

    public Map<String, Object> canonical() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action); out.put("product", product); out.put("warehouse", warehouse); out.put("location", location); out.put("quantity", quantity);
        out.put("reason", reason); out.put("batch", batch); out.put("serial", serial); out.put("expiry", expiry); out.put("toWarehouse", toWarehouse); out.put("toLocation", toLocation);
        return out;
    }

    public String hash() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(canonical()).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String identifier(Map<String, Object> input, String key) {
        String value = text(input, key, 64, true);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw WarehouseException.invalid(key + " is not a valid identifier");
        return value;
    }
    private static String text(Map<String, Object> input, String key, int maximum, boolean required) {
        Object value = input.get(key);
        if (value != null && !(value instanceof String)) throw WarehouseException.invalid(key + " must be text");
        String out = value == null ? "" : ((String) value).strip();
        if (out.length() > maximum || out.indexOf('\0') >= 0 || required && out.isEmpty()) throw WarehouseException.invalid(key + " is missing or exceeds text limits");
        return out;
    }
}
