package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.inventory.StocktakeLock;
import com.ruoyi.warehouse.support.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Package-private stock engine: called only inside OrderService transactions. */
@Component
class OrderStock {
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final JdbcTemplate jdbc;
    OrderStock(WarehouseRepository records, WarehouseScope scope) { this.records = records; this.scope = scope; jdbc = records.jdbc(); }

    List<Map<String, Object>> view(String order) {
        Map<String, Object> owner = records.scoped("orders", order, false);
        return jdbc.query("SELECT a.*,i.product_id,i.warehouse_id,i.location_id,i.batch_number,i.serial_number,i.expiry_date,s.origin_id,s.received_at,p.business_json product_json,w.display_name warehouse_name,l.display_name location_name FROM wh_order_allocation a JOIN wh_inventory i ON i.id=a.inventory_id JOIN wh_stock_layer s ON s.id=a.layer_id JOIN wh_record p ON p.id=i.product_id AND p.kind='products' AND p.client_id=i.client_id JOIN wh_record w ON w.id=i.warehouse_id AND w.kind='warehouses' JOIN wh_record l ON l.id=i.location_id AND l.kind='locations' AND l.warehouse_id=i.warehouse_id WHERE a.order_id=? AND i.client_id=? AND i.warehouse_id=? ORDER BY a.created_at,a.id", (rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String[] field : List.of(new String[]{"id", "id"}, new String[]{"inventory_id", "inventoryId"}, new String[]{"layer_id", "layerId"}, new String[]{"product_id", "product"}, new String[]{"warehouse_id", "warehouse"}, new String[]{"location_id", "location"}, new String[]{"batch_number", "batch"}, new String[]{"serial_number", "serial"}, new String[]{"expiry_date", "expiry"}, new String[]{"origin_id", "originId"}, new String[]{"state", "state"})) row.put(field[1], rs.getString(field[0]));
            Map<String, Object> product = WarehouseJson.map(rs.getString("product_json"));
            row.put("quantity", rs.getLong("quantity")); row.put("receivedAt", rs.getTimestamp("received_at").toInstant().toString());
            row.put("sku", text(product, "sku")); row.put("productName", text(product, "name"));
            row.put("warehouseName", rs.getString("warehouse_name")); row.put("locationName", rs.getString("location_name"));
            row.put("expiryWarning", expiryIssue(product, rs.getString("expiry_date")));
            return row;
        }, order, text(owner, "client"), text(owner, "warehouse"));
    }

    boolean hasHistory(String order) { return !jdbc.query("SELECT id FROM wh_order_allocation WHERE order_id=? FOR UPDATE", (rs, n) -> rs.getString(1), order).isEmpty(); }

    void allocate(Map<String, Object> order, Map<String, Object> client, Map<String, Map<String, Object>> products, String operation) {
        String orderId = text(order, "id"), warehouse = text(order, "warehouse");
        if (hasHistory(orderId)) throw WarehouseException.conflict("Order already has allocation history");
        Map<String, Map<String, Object>> locations = lockLocations(warehouse);
        Map<String, Map<String, Object>> types = lockTypes(locations);
        for (Map<String, Object> line : WarehouseJson.maps(order.get("lines"))) {
            String productId = text(line, "product"); Map<String, Object> product = products.get(productId); long remaining = quantity(line.get("quantity"));
            List<Candidate> candidates = new ArrayList<>();
            for (Pool pool : pools(productId, warehouse)) {
                List<Layer> layers = consistent(pool);
                Map<String, Object> location = locations.get(pool.location());
                if (!eligibleLocation(location, types, text(order, "client")) || !eligibleLot(product, client, pool)) continue;
                if (!StocktakeLock.owner(jdbc, pool.client(), warehouse, pool.location()).isEmpty()) continue;
                if (!pool.client().equals(text(order, "client"))) throw WarehouseException.conflict("Stock belongs to another client");
                int priority = priority(types.get(text(location, "type")));
                for (Layer layer : layers) if (layer.remaining() > layer.reserved()) candidates.add(new Candidate(pool, layer, priority, pool.location().equals(text(product, "defaultLocation"))));
            }
            boolean fifo = "FIFO".equals(client.get("pickingLogic"));
            Comparator<Candidate> comparator = fifo ? Comparator.comparing(c -> c.layer().received()) : (a, b) -> 0;
            comparator = comparator.thenComparing(c -> !c.preferred()).thenComparingInt(Candidate::priority).thenComparing(c -> c.pool().expiry().isEmpty() ? "9999" : c.pool().expiry()).thenComparingLong(c -> c.pool().onhand()).thenComparing(c -> c.pool().location()).thenComparing(c -> c.layer().received()).thenComparing(c -> c.layer().id());
            candidates.sort(comparator);
            for (Candidate candidate : candidates) {
                long take = Math.min(remaining, candidate.layer().remaining() - candidate.layer().reserved());
                if (take == 0) continue;
                if (jdbc.update("UPDATE wh_stock_layer SET reserved_quantity=reserved_quantity+? WHERE id=? AND remaining_quantity-reserved_quantity>=?", take, candidate.layer().id(), take) != 1 || jdbc.update("UPDATE wh_inventory SET reserved=reserved+?,revision=revision+1 WHERE id=? AND on_hand-reserved>=?", take, candidate.pool().id(), take) != 1) throw WarehouseException.conflict("Stock changed during allocation");
                jdbc.update("INSERT INTO wh_order_allocation(id,order_id,inventory_id,layer_id,quantity,state) VALUES(?,?,?,?,?,'RESERVED')", UUID.randomUUID().toString(), orderId, candidate.pool().id(), candidate.layer().id(), take);
                ledger(operation, candidate.pool().id(), candidate.layer().id(), 0, take, orderId, "Order allocation");
                remaining -= take; if (remaining == 0) break;
            }
            if (remaining != 0) throw WarehouseException.conflict("Insufficient eligible stock for " + text(product, "sku") + " / 可分配库存不足");
        }
        validate(order, client, products, true);
    }

    void validate(Map<String, Object> order, Map<String, Object> client, Map<String, Map<String, Object>> products, boolean outbound) {
        Map<String, Map<String, Object>> locations = lockLocations(text(order, "warehouse"));
        Map<String, Map<String, Object>> types = outbound ? lockTypes(locations) : Map.of();
        Map<String, Long> expected = new HashMap<>(), actual = new HashMap<>();
        for (Map<String, Object> line : WarehouseJson.maps(order.get("lines"))) expected.put(text(line, "product"), quantity(line.get("quantity")));
        Map<String, Pool> pools = new HashMap<>();
        for (Allocation allocation : allocations(text(order, "id"))) {
            Pool pool = pools.computeIfAbsent(allocation.pool(), this::pool);
            StocktakeLock.available(jdbc, pool.client(), pool.warehouse(), pool.location());
            consistent(pool);
            if (!text(order, "client").equals(pool.client()) || !text(order, "warehouse").equals(pool.warehouse()) || !expected.containsKey(pool.product())) throw WarehouseException.conflict("Order allocation ownership is inconsistent");
            Layer layer = layer(allocation.layer());
            if (!layer.pool().equals(pool.id()) || layer.reserved() < allocation.quantity() || pool.reserved() < allocation.quantity()) throw WarehouseException.conflict("Order reservation evidence is incomplete");
            if (outbound && (!eligibleLocation(locations.get(pool.location()), types, pool.client()) || !eligibleLot(products.get(pool.product()), client, pool))) throw WarehouseException.conflict("Reserved stock no longer meets location or outbound expiry policy / 已预留库存不满足出库条件");
            actual.merge(pool.product(), allocation.quantity(), Math::addExact);
        }
        if (!expected.equals(actual)) throw WarehouseException.conflict("Order reservation quantities do not match its lines / 订单预留数量不完整");
    }

    void releaseOrConsume(Map<String, Object> order, Map<String, Object> client, Map<String, Map<String, Object>> products, String operation, boolean consume) {
        List<Allocation> allocations = allocations(text(order, "id"));
        if (allocations.isEmpty()) { if (consume) throw WarehouseException.conflict("Order has no reservations"); return; }
        validate(order, client, products, consume);
        Set<String> changedPools = new HashSet<>();
        for (Allocation allocation : allocations) {
            long quantity = allocation.quantity(); long delta = consume ? quantity : 0;
            if (jdbc.update("UPDATE wh_stock_layer SET remaining_quantity=remaining_quantity-?,reserved_quantity=reserved_quantity-? WHERE id=? AND remaining_quantity>=? AND reserved_quantity>=?", delta, quantity, allocation.layer(), delta, quantity) != 1 || jdbc.update("UPDATE wh_inventory SET on_hand=on_hand-?,reserved=reserved-?,revision=revision+1 WHERE id=? AND on_hand>=? AND reserved>=?", delta, quantity, allocation.pool(), delta, quantity) != 1) throw WarehouseException.conflict("Order stock reservation changed");
            if (jdbc.update("UPDATE wh_order_allocation SET state=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='RESERVED'", consume ? "CONSUMED" : "RELEASED", allocation.id()) != 1) throw WarehouseException.conflict("Allocation was already resolved");
            ledger(operation, allocation.pool(), allocation.layer(), -delta, -quantity, text(order, "id"), consume ? "Manual order despatch" : "Order cancellation release");
            changedPools.add(allocation.pool());
        }
        for (String id : changedPools) {consistent(pool(id));if(consume)com.ruoyi.warehouse.finance.StorageHistory.inventory(jdbc,id);}
    }

    private Map<String, Map<String, Object>> lockLocations(String warehouse) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String id : jdbc.query("SELECT id FROM wh_record WHERE kind='locations' AND warehouse_id=? ORDER BY id FOR UPDATE", (rs, n) -> rs.getString(1), warehouse)) result.put(id, records.get("locations", id, true));
        return result;
    }
    private Map<String, Map<String, Object>> lockTypes(Map<String, Map<String, Object>> locations) {
        Set<String> names = new HashSet<>(); locations.values().forEach(location -> names.add(text(location, "type")));
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (String id : jdbc.query("SELECT id FROM wh_record WHERE kind='locationTypes' ORDER BY id FOR UPDATE", (rs, n) -> rs.getString(1))) { var type = records.get("locationTypes", id, true); if (names.contains(text(type, "name"))) result.put(text(type, "name"), type); }
        return result;
    }
    private boolean eligibleLocation(Map<String, Object> location, Map<String, Map<String, Object>> types, String client) {
        if (location == null || inactive(location) || enabled(location, "custodyOnly") || "Quarantine".equals(location.get("type"))) return false;
        Map<String, Object> type = types.get(text(location, "type"));
        return type != null && !inactive(type) && (text(location, "client").isEmpty() || client.equals(text(location, "client")));
    }
    static boolean eligibleLot(Map<String, Object> product, Map<String, Object> client, Pool pool) {
        if (product == null) return false;
        if ("Required".equals(product.get("logBatchOutbound")) && pool.batch().isEmpty() || "Required".equals(product.get("logSerialOutbound")) && pool.serial().isEmpty() || "Required".equals(product.get("logExpiryOutbound")) && pool.expiry().isEmpty()) return false;
        if (!pool.serial().isEmpty() && pool.onhand() > 1) return false;
        String configured = text(product, "expiryPolicy"), policy = Set.of("Warn", "BlockExpired").contains(configured) ? configured : "BlockExpired".equals(client.get("expiryPolicy")) ? "BlockExpired" : "Warn";
        return !"BlockExpired".equals(policy) || expiryIssue(product, pool.expiry()).isEmpty();
    }

    static String expiryIssue(Map<String, Object> product, String expiry) {
        if (expiry.isEmpty()) return enabled(product, "hasExpiry") ? "missing expiry date" : "";
        try {
            if (!expiry.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return "invalid expiry date";
            return LocalDate.parse(expiry, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)).isBefore(LocalDate.now(ZoneOffset.UTC)) ? "expired stock (" + expiry + ")" : "";
        } catch (RuntimeException invalid) { return "invalid expiry date"; }
    }

    private List<Pool> pools(String product, String warehouse) { return jdbc.query("SELECT * FROM wh_inventory WHERE product_id=? AND warehouse_id=? ORDER BY id FOR UPDATE", (rs, n) -> pool(rs), product, warehouse); }
    private Pool pool(String id) { return jdbc.queryForObject("SELECT * FROM wh_inventory WHERE id=? FOR UPDATE", (rs, n) -> pool(rs), id); }
    private static Pool pool(ResultSet rs) throws SQLException { return new Pool(rs.getString("id"), rs.getString("client_id"), rs.getString("product_id"), rs.getString("warehouse_id"), rs.getString("location_id"), rs.getString("batch_number"), rs.getString("serial_number"), rs.getString("expiry_date"), rs.getLong("on_hand"), rs.getLong("reserved")); }
    private Layer layer(String id) { return jdbc.queryForObject("SELECT * FROM wh_stock_layer WHERE id=? FOR UPDATE", (rs, n) -> layer(rs), id); }
    private static Layer layer(ResultSet rs) throws SQLException { return new Layer(rs.getString("id"), rs.getString("inventory_id"), rs.getString("origin_id"), rs.getTimestamp("received_at"), rs.getLong("remaining_quantity"), rs.getLong("reserved_quantity")); }
    private List<Layer> consistent(Pool pool) {
        List<Layer> layers = jdbc.query("SELECT * FROM wh_stock_layer WHERE inventory_id=? ORDER BY received_at,id FOR UPDATE", (rs, n) -> layer(rs), pool.id()); long quantity = 0, reserved = 0;
        for (Layer layer : layers) {
            if (layer.remaining() < 0 || layer.reserved() < 0 || layer.reserved() > layer.remaining() || layer.received() == null || layer.origin() == null || layer.origin().isBlank()) throw WarehouseException.conflict("Invalid stock layer evidence");
            quantity = Math.addExact(quantity, layer.remaining()); reserved = Math.addExact(reserved, layer.reserved());
            long promised = jdbc.query("SELECT quantity FROM wh_order_allocation WHERE layer_id=? AND state='RESERVED' FOR UPDATE", (rs, n) -> rs.getLong(1), layer.id()).stream().mapToLong(Long::longValue).sum();
            if (promised != layer.reserved()) throw WarehouseException.conflict("Reserved stock has no matching order allocation evidence");
        }
        if (quantity != pool.onhand() || reserved != pool.reserved()) throw WarehouseException.conflict("Inventory and source layer totals disagree"); return layers;
    }
    private List<Allocation> allocations(String order) { return jdbc.query("SELECT * FROM wh_order_allocation WHERE order_id=? AND state='RESERVED' ORDER BY inventory_id,layer_id FOR UPDATE", (rs, n) -> new Allocation(rs.getString("id"), rs.getString("inventory_id"), rs.getString("layer_id"), rs.getLong("quantity")), order); }
    private void ledger(String operation, String pool, String layer, long delta, long reserved, String order, String reason) { jdbc.update("INSERT INTO wh_ledger(operation_id,inventory_id,layer_id,quantity_delta,reserved_delta,reference_id,reason,actor_id) VALUES(?,?,?,?,?,?,?,?)", operation, pool, layer, delta, reserved, order, reason, scope.actor()); }
    private static long quantity(Object value) { return WarehouseJson.integer(value, "quantity", 1, 10_000_000); }
    private static int priority(Map<String, Object> type) { Object value = type == null ? null : type.get("pickPriority"); return value == null || "".equals(value) ? 0 : (int) WarehouseJson.integer(value, "pickPriority", 0, Integer.MAX_VALUE); }
    private static String text(Map<String, Object> row, String key) { return WarehouseJson.text(row, key); }
    private static boolean enabled(Map<String, Object> row, String key) { return WarehouseJson.enabled(row.get(key)); }
    private static boolean inactive(Map<String, Object> row) { return Boolean.FALSE.equals(row.get("active")) || "N".equals(row.get("active")); }
    record Pool(String id, String client, String product, String warehouse, String location, String batch, String serial, String expiry, long onhand, long reserved) { }
    private record Layer(String id, String pool, String origin, Timestamp received, long remaining, long reserved) { }
    private record Candidate(Pool pool, Layer layer, int priority, boolean preferred) { }
    private record Allocation(String id, String pool, String layer, long quantity) { }
}
