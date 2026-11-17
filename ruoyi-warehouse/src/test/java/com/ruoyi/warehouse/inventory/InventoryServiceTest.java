package com.ruoyi.warehouse.inventory;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.WarehouseException;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SQL and rollback tests on isolated H2; no application/server DB is read. */
class InventoryServiceTest {
    private JdbcTemplate jdbc;
    private InventoryService service;
    private WarehouseScope scope;
    private TransactionTemplate transaction;

    @BeforeEach
    void database() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:inventory_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(ds); transaction = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE wh_record(id VARCHAR(64) PRIMARY KEY,kind VARCHAR(64),client_id VARCHAR(64) DEFAULT '',warehouse_id VARCHAR(64) DEFAULT '',business_json VARCHAR(100000),display_name VARCHAR(255),natural_key VARCHAR(191),revision BIGINT DEFAULT 1,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE wh_inventory(id VARCHAR(64) PRIMARY KEY,client_id VARCHAR(64) REFERENCES wh_record(id),product_id VARCHAR(64) REFERENCES wh_record(id),warehouse_id VARCHAR(64) REFERENCES wh_record(id),location_id VARCHAR(64) REFERENCES wh_record(id),batch_number VARCHAR(191),serial_number VARCHAR(191),expiry_date VARCHAR(10),on_hand BIGINT CHECK(on_hand>=0),reserved BIGINT CHECK(reserved>=0 AND reserved<=on_hand),revision BIGINT,UNIQUE(product_id,warehouse_id,location_id,batch_number,serial_number,expiry_date))");
        jdbc.execute("CREATE TABLE wh_stock_layer(id VARCHAR(64) PRIMARY KEY,inventory_id VARCHAR(64) REFERENCES wh_inventory(id),origin_id VARCHAR(64),received_at TIMESTAMP(6),remaining_quantity BIGINT CHECK(remaining_quantity>=0),reserved_quantity BIGINT CHECK(reserved_quantity>=0 AND reserved_quantity<=remaining_quantity))");
        jdbc.execute("CREATE TABLE wh_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id VARCHAR(64),inventory_id VARCHAR(64) REFERENCES wh_inventory(id),layer_id VARCHAR(64) REFERENCES wh_stock_layer(id),quantity_delta BIGINT,reserved_delta BIGINT,reference_id VARCHAR(64),reason VARCHAR(500),actor_id BIGINT)");
        jdbc.execute("CREATE TABLE wh_operation_request(actor_id BIGINT,request_id VARCHAR(64),operation_type VARCHAR(64),request_hash CHAR(64),response_json VARCHAR(100000),PRIMARY KEY(actor_id,request_id))");
        jdbc.execute("CREATE TABLE wh_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT,action_name VARCHAR(80),record_id VARCHAR(64),record_kind VARCHAR(64),before_json VARCHAR(100000),after_json VARCHAR(100000))");
        scope = mock(WarehouseScope.class); when(scope.actor()).thenReturn(7L); when(scope.granted(anyString(), anyString())).thenReturn(true); when(scope.inventoryPredicate(anyList())).thenReturn("1=1");
        com.ruoyi.warehouse.stocktake.StocktakeSchema.create(jdbc);
        com.ruoyi.warehouse.finance.StorageSchema.create(jdbc);
        service = new InventoryService(new WarehouseRepository(jdbc, scope), scope);
        record("client", "clients", map("shortName", "Synthetic Client"));
        record("product", "products", map("client", "client", "sku", "SYNTHETIC", "name", "Synthetic product"));
        record("warehouse", "warehouses", map("name", "Synthetic warehouse", "active", true));
        record("warehouse2", "warehouses", map("name", "Synthetic warehouse 2", "active", true));
        record("type", "locationTypes", map("name", "Pick", "active", true, "maxUnits", 0));
        record("location", "locations", map("name", "A-1", "warehouse", "warehouse", "type", "Pick", "active", true));
        record("destination", "locations", map("name", "B-1", "warehouse", "warehouse2", "type", "Pick", "active", true));
    }

    @Test
    void sameRequestReturnsOriginalResultAndChangedPayloadCannotReuseIt() {
        var input = request("add", 12); var first = move(input); var repeated = move(input);
        assertEquals(WarehouseJson.write(first), WarehouseJson.write(repeated));
        assertEquals(12, count("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(1, count("SELECT COUNT(*) FROM wh_ledger"));
        assertEquals(1, count("SELECT COUNT(*) FROM wh_audit")); assertEquals(false, first.get("billingTriggered"));
        input.put("quantity", 11); assertThrows(WarehouseException.class, () -> move(input));
        assertEquals(12, count("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(1, count("SELECT COUNT(*) FROM wh_operation_request"));
    }

    @Test
    void transferKeepsOriginalReceiptAgeAndNeverMovesReservations() {
        move(request("add", 10)); String origin = jdbc.queryForObject("SELECT origin_id FROM wh_stock_layer", String.class);
        Timestamp received = Timestamp.from(Instant.parse("2024-02-01T12:34:56.123456Z"));
        jdbc.update("UPDATE wh_stock_layer SET received_at=?,reserved_quantity=2", received); jdbc.update("UPDATE wh_inventory SET reserved=2");
        var transfer = request("transfer", 5); transfer.put("toWarehouse", "warehouse2"); transfer.put("toLocation", "destination");
        var result = move(transfer);
        assertEquals(5, count("SELECT on_hand FROM wh_inventory WHERE location_id='location'"));
        assertEquals(2, count("SELECT reserved FROM wh_inventory WHERE location_id='location'"));
        assertEquals(5, count("SELECT on_hand FROM wh_inventory WHERE location_id='destination'"));
        assertEquals(origin, jdbc.queryForObject("SELECT s.origin_id FROM wh_stock_layer s JOIN wh_inventory i ON i.id=s.inventory_id WHERE i.location_id='destination'", String.class));
        assertEquals(received, jdbc.queryForObject("SELECT s.received_at FROM wh_stock_layer s JOIN wh_inventory i ON i.id=s.inventory_id WHERE i.location_id='destination'", Timestamp.class));
        assertEquals(2, WarehouseJson.maps(result.get("inventory")).size());
        assertThrows(WarehouseException.class, () -> move(request("remove", 4)));
        assertEquals(2, count("SELECT COUNT(*) FROM wh_operation_request"));
        assertEquals(10, count("SELECT SUM(on_hand) FROM wh_inventory"));
        assertEquals(10, count("SELECT SUM(quantity_delta) FROM wh_ledger"));
    }

    @Test
    void removalUsesFifoFreeLayerSlicesAndRejectsMissingSourceEvidence() {
        move(request("add", 4)); jdbc.update("UPDATE wh_stock_layer SET received_at=?", Timestamp.from(Instant.parse("2023-01-01T00:00:00Z")));
        move(request("add", 6)); move(request("remove", 8));
        assertEquals(List.of(0L, 2L), jdbc.query("SELECT remaining_quantity FROM wh_stock_layer ORDER BY received_at", (rs, n) -> rs.getLong(1)));
        assertEquals(2, count("SELECT COUNT(*) FROM wh_ledger WHERE quantity_delta<0"));
        jdbc.update("UPDATE wh_inventory SET on_hand=3");
        var bad = request("remove", 1); assertThrows(WarehouseException.class, () -> move(bad));
        assertEquals(0, count("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='" + bad.get("requestId") + "'"));
        assertEquals(2, count("SELECT SUM(remaining_quantity) FROM wh_stock_layer"));
    }

    @Test
    void serialUniquenessSpansWarehousesButTransferRetainsTheSameSerial() {
        updateRecord("product", map("client", "client", "sku", "SYNTHETIC", "hasBatch", true, "hasSerial", true, "hasExpiry", true));
        assertThrows(WarehouseException.class, () -> move(request("add", 1)));
        var add = request("add", 1); add.put("batch", "LOT-1"); add.put("serial", "SERIAL-1"); add.put("expiry", "2028-02-29"); move(add);
        var duplicate = new LinkedHashMap<>(add); duplicate.put("requestId", UUID.randomUUID().toString()); duplicate.put("warehouse", "warehouse2"); duplicate.put("location", "destination");
        assertThrows(WarehouseException.class, () -> move(duplicate));
        var transfer = new LinkedHashMap<>(add); transfer.put("requestId", UUID.randomUUID().toString()); transfer.put("action", "transfer"); transfer.put("toWarehouse", "warehouse2"); transfer.put("toLocation", "destination"); move(transfer);
        assertEquals(1, count("SELECT SUM(on_hand) FROM wh_inventory WHERE serial_number='SERIAL-1'"));
        assertEquals(1, count("SELECT on_hand FROM wh_inventory WHERE location_id='destination'"));
    }

    @Test
    void capacityIncludesOtherProductsAndInheritsTypeUnlessPositiveOverride() {
        updateRecord("type", map("name", "Pick", "active", true, "maxUnits", 5));
        record("product2", "products", map("client", "client", "sku", "SECOND"));
        move(request("add", 4)); var second = request("add", 2); second.put("product", "product2");
        assertThrows(WarehouseException.class, () -> move(second));
        assertEquals(4, count("SELECT SUM(on_hand) FROM wh_inventory"));
        updateRecord("location", map("name", "A-1", "warehouse", "warehouse", "type", "Pick", "active", true, "maxUnits", 10));
        move(second); assertEquals(6, count("SELECT SUM(on_hand) FROM wh_inventory"));
    }

    @Test
    void auditFailureRollsBackPoolsLayersLedgerAndRequestClaim() {
        jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_add CHECK(action_name<>'MANUAL_STOCK_ADD')");
        var input = request("add", 5); assertThrows(RuntimeException.class, () -> move(input));
        for (String table : List.of("wh_inventory", "wh_stock_layer", "wh_ledger", "wh_operation_request", "wh_audit")) assertEquals(0, count("SELECT COUNT(*) FROM " + table), table);
        jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_add"); move(input);
        assertEquals(5, count("SELECT SUM(on_hand) FROM wh_inventory"));
    }

    @Test
    void scopeIsRecheckedEvenForACompletedRequest() {
        var input = request("add", 2); move(input);
        when(scope.granted("CLIENT", "client")).thenReturn(false);
        assertThrows(WarehouseException.class, () -> move(input));
        assertEquals(1, count("SELECT COUNT(*) FROM wh_operation_request"));
    }

    @Test
    void rejectsCustodyInactiveWrongWarehouseAndVirtualProducts() {
        updateRecord("location", map("name", "A-1", "warehouse", "warehouse", "type", "Quarantine", "active", true, "custodyOnly", true));
        assertThrows(WarehouseException.class, () -> move(request("add", 1)));
        updateRecord("location", map("name", "A-1", "warehouse", "warehouse2", "type", "Pick", "active", true));
        assertThrows(WarehouseException.class, () -> move(request("add", 1)));
        updateRecord("location", map("name", "A-1", "warehouse", "warehouse", "type", "Pick", "active", false));
        assertThrows(WarehouseException.class, () -> move(request("add", 1)));
        updateRecord("location", map("name", "A-1", "warehouse", "warehouse", "type", "Pick", "active", true));
        for (String flag : List.of("bundle", "dropship", "infiniteStock")) {
            updateRecord("product", map("client", "client", "sku", "SYNTHETIC", flag, true));
            assertThrows(WarehouseException.class, () -> move(request("add", 1)));
        }
        assertEquals(0, count("SELECT COUNT(*) FROM wh_operation_request"));
    }

    @Test
    void concurrentSameRequestIsAppliedExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            var input = request("add", 3);
            Callable<Map<String, Object>> operation = () -> { start.await(); return move(input); };
            var first = executor.submit(operation); var second = executor.submit(operation); start.countDown();
            assertEquals(WarehouseJson.write(first.get(10, TimeUnit.SECONDS)), WarehouseJson.write(second.get(10, TimeUnit.SECONDS)));
            assertEquals(3, count("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(1, count("SELECT COUNT(*) FROM wh_ledger"));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void concurrentProductsCannotOverfillTheSameLocation() throws Exception {
        updateRecord("type", map("name", "Pick", "active", true, "maxUnits", 5));
        record("client2", "clients", map("shortName", "Second synthetic client"));
        record("product2", "products", map("client", "client2", "sku", "SECOND"));
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            var firstRequest = request("add", 4); var secondRequest = request("add", 4); secondRequest.put("product", "product2");
            Callable<Boolean> firstCall = () -> { start.await(); try { move(firstRequest); return true; } catch (WarehouseException denied) { return false; } };
            Callable<Boolean> secondCall = () -> { start.await(); try { move(secondRequest); return true; } catch (WarehouseException denied) { return false; } };
            var first = executor.submit(firstCall); var second = executor.submit(secondCall); start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(4, count("SELECT SUM(on_hand) FROM wh_inventory"));
            assertEquals(1, count("SELECT COUNT(*) FROM wh_operation_request"));
            assertEquals(1, count("SELECT COUNT(*) FROM wh_audit"));
        } finally { executor.shutdownNow(); }
    }

    @Test
    void newLayerTimestampMatchesTheStoredMicrosecondEvidence() {
        var result = move(request("add", 1));
        String responseDate = String.valueOf(WarehouseJson.maps(result.get("layers")).get(0).get("receivedAt"));
        assertEquals(jdbc.queryForObject("SELECT received_at FROM wh_stock_layer", Timestamp.class).toInstant().toString(), responseDate);
    }

    @Test
    void inactiveSourceWarehouseAndLocationCanBeEmptiedOrTransferred() {
        move(request("add", 10));
        updateRecord("warehouse", map("name", "Synthetic warehouse", "active", false));
        updateRecord("location", map("name", "A-1", "warehouse", "warehouse", "type", "Pick", "active", false));
        move(request("remove", 4));
        var transfer = request("transfer", 2); transfer.put("toWarehouse", "warehouse2"); transfer.put("toLocation", "destination");
        move(transfer);
        assertEquals(4, count("SELECT on_hand FROM wh_inventory WHERE location_id='location'"));
        assertEquals(2, count("SELECT on_hand FROM wh_inventory WHERE location_id='destination'"));
        assertEquals(6, count("SELECT SUM(remaining_quantity) FROM wh_stock_layer"));
        assertEquals(0, count("SELECT SUM(reserved) FROM wh_inventory"));
        assertThrows(WarehouseException.class, () -> move(request("add", 1)));
    }

    @Test
    void inactiveTransferDestinationRejectsWithoutChangingSourceOrJournal() {
        move(request("add", 5));
        var transfer = request("transfer", 2); transfer.put("toWarehouse", "warehouse2"); transfer.put("toLocation", "destination");
        updateRecord("destination", map("name", "B-1", "warehouse", "warehouse2", "type", "Pick", "active", false));
        assertThrows(WarehouseException.class, () -> move(transfer));
        updateRecord("destination", map("name", "B-1", "warehouse", "warehouse2", "type", "Pick", "active", true));
        updateRecord("warehouse2", map("name", "Synthetic warehouse 2", "active", false));
        assertThrows(WarehouseException.class, () -> move(transfer));
        assertEquals(5, count("SELECT SUM(on_hand) FROM wh_inventory"));
        assertEquals(5, count("SELECT SUM(remaining_quantity) FROM wh_stock_layer"));
        assertEquals(1, count("SELECT COUNT(*) FROM wh_inventory"));
        assertEquals(1, count("SELECT COUNT(*) FROM wh_operation_request"));
        assertEquals(1, count("SELECT COUNT(*) FROM wh_ledger"));
        assertEquals(1, count("SELECT COUNT(*) FROM wh_audit"));
    }

    @Test
    void inventoryListFiltersAndExposesAgeAndAvailableQuantity() {
        move(request("add", 3));
        var response = service.list(1, 20, "client", "product", "warehouse", "SYNTHETIC");
        assertEquals(1L, response.get("total")); var row = WarehouseJson.maps(response.get("rows")).get(0);
        assertEquals(3L, row.get("available")); assertNotNull(row.get("oldestReceivedAt")); assertEquals("SYNTHETIC", row.get("sku"));
        assertEquals(0L, service.list(1, 20, "other", "", "", "").get("total"));
        assertThrows(WarehouseException.class, () -> service.list(0, 20, "", "", "", ""));
    }

    @Test
    void requestRejectsUntrackedBackdatingInvalidDatesAndAmbiguousPayloads() {
        var backdated = request("add", 1); backdated.put("receivedAt", "2020-01-01"); assertThrows(WarehouseException.class, () -> InventoryMovement.parse(backdated));
        for (String expiry : List.of("2026-02-29", "2026-1-01", "2026-01-01T00:00:00Z", "0000-01-01")) {
            var invalid = request("add", 1); invalid.put("expiry", expiry); assertThrows(WarehouseException.class, () -> InventoryMovement.parse(invalid));
        }
        var serial = request("add", 2); serial.put("serial", "ONE"); assertThrows(WarehouseException.class, () -> InventoryMovement.parse(serial));
        var same = request("transfer", 1); same.put("toWarehouse", "warehouse"); same.put("toLocation", "location"); assertThrows(WarehouseException.class, () -> InventoryMovement.parse(same));
        var zero = request("add", 0); assertThrows(WarehouseException.class, () -> InventoryMovement.parse(zero));
    }

    private Map<String, Object> move(Map<String, Object> input) { return transaction.execute(status -> service.move(input)); }
    private long count(String sql) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class)); }
    private Map<String, Object> request(String action, long quantity) { return map("requestId", UUID.randomUUID().toString(), "action", action, "product", "product", "warehouse", "warehouse", "location", "location", "quantity", quantity, "reason", "Synthetic manual stock check"); }
    private void record(String id, String kind, Map<String, Object> data) { jdbc.update("INSERT INTO wh_record(id,kind,client_id,warehouse_id,business_json,display_name,natural_key) VALUES(?,?,?,?,?,?,?)", id, kind, WarehouseJson.text(data, "client"), WarehouseJson.text(data, "warehouse"), WarehouseJson.write(data), data.getOrDefault("sku", data.getOrDefault("shortName", data.getOrDefault("name", id))), id); }
    private void updateRecord(String id, Map<String, Object> data) { jdbc.update("UPDATE wh_record SET business_json=? WHERE id=?", WarehouseJson.write(data), id); }
    private static Map<String, Object> map(Object... values) { Map<String, Object> out = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]); return out; }
}
