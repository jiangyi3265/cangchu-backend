package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.finance.FinancePeriodService;
import com.ruoyi.warehouse.inventory.InventoryService;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
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

/** Real isolated JDBC transactions; each test starts with synthetic records only. */
class OrderServiceTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    OrderService service;
    InventoryService inventory;
    WarehouseScope scope;
    Map<String, Object> client;

    @BeforeEach void database() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:orders_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        jdbc = new JdbcTemplate(ds); tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE wh_record(id VARCHAR(64) PRIMARY KEY,kind VARCHAR(64),client_id VARCHAR(64) DEFAULT '',warehouse_id VARCHAR(64) DEFAULT '',scope_key VARCHAR(140) DEFAULT '',natural_key VARCHAR(191),display_name VARCHAR(255),business_json VARCHAR(1000000),revision BIGINT DEFAULT 1,created_by BIGINT DEFAULT 7,updated_by BIGINT DEFAULT 7,created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),UNIQUE(kind,scope_key,natural_key))");
        jdbc.execute("CREATE TABLE wh_reference(owner_id VARCHAR(64) REFERENCES wh_record(id),target_id VARCHAR(64) REFERENCES wh_record(id),field_name VARCHAR(64),PRIMARY KEY(owner_id,target_id,field_name))");
        jdbc.execute("CREATE TABLE wh_inventory(id VARCHAR(64) PRIMARY KEY,client_id VARCHAR(64) REFERENCES wh_record(id),product_id VARCHAR(64) REFERENCES wh_record(id),warehouse_id VARCHAR(64) REFERENCES wh_record(id),location_id VARCHAR(64) REFERENCES wh_record(id),batch_number VARCHAR(191),serial_number VARCHAR(191),expiry_date VARCHAR(10),on_hand BIGINT CHECK(on_hand>=0),reserved BIGINT CHECK(reserved>=0 AND reserved<=on_hand),revision BIGINT,UNIQUE(product_id,warehouse_id,location_id,batch_number,serial_number,expiry_date))");
        jdbc.execute("CREATE TABLE wh_stock_layer(id VARCHAR(64) PRIMARY KEY,inventory_id VARCHAR(64) REFERENCES wh_inventory(id),origin_id VARCHAR(64),received_at TIMESTAMP(6),remaining_quantity BIGINT CHECK(remaining_quantity>=0),reserved_quantity BIGINT CHECK(reserved_quantity>=0 AND reserved_quantity<=remaining_quantity))");
        jdbc.execute("CREATE TABLE wh_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id VARCHAR(64),inventory_id VARCHAR(64) REFERENCES wh_inventory(id),layer_id VARCHAR(64) REFERENCES wh_stock_layer(id),quantity_delta BIGINT,reserved_delta BIGINT,reference_id VARCHAR(64),reason VARCHAR(500),actor_id BIGINT)");
        jdbc.execute("CREATE TABLE wh_operation_request(actor_id BIGINT,request_id VARCHAR(64),operation_type VARCHAR(64),request_hash CHAR(64),response_json VARCHAR(1000000),PRIMARY KEY(actor_id,request_id))");
        jdbc.execute("CREATE TABLE wh_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT,action_name VARCHAR(80),record_id VARCHAR(64),record_kind VARCHAR(64),before_json VARCHAR(1000000),after_json VARCHAR(1000000))");
        jdbc.execute("CREATE TABLE wh_order_allocation(id VARCHAR(64) PRIMARY KEY,order_id VARCHAR(64) REFERENCES wh_record(id),inventory_id VARCHAR(64) REFERENCES wh_inventory(id),layer_id VARCHAR(64) REFERENCES wh_stock_layer(id),quantity BIGINT CHECK(quantity>0),state VARCHAR(16) CHECK(state IN('RESERVED','RELEASED','CONSUMED')),created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),UNIQUE(order_id,layer_id))");
        jdbc.execute("CREATE TABLE wh_order_billing(order_id VARCHAR(64) PRIMARY KEY REFERENCES wh_record(id),charge_id VARCHAR(64) UNIQUE REFERENCES wh_record(id),source_snapshot VARCHAR(1000000),quote_json VARCHAR(1000000),created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE wh_finance_period(id CHAR(64) PRIMARY KEY,rule_id VARCHAR(64) NOT NULL REFERENCES wh_record(id),client_id VARCHAR(64) NOT NULL REFERENCES wh_record(id),from_date DATE NOT NULL,to_date DATE NOT NULL,currency VARCHAR(3) NOT NULL,snapshot_json VARCHAR(1000000) NOT NULL,settled_count BIGINT NOT NULL DEFAULT 0,settled_amount DECIMAL(24,2) NOT NULL DEFAULT 0,UNIQUE(rule_id,from_date,to_date))");
        jdbc.execute("CREATE TABLE wh_finance_tier_order(order_id VARCHAR(64) PRIMARY KEY REFERENCES wh_record(id),period_id CHAR(64) NOT NULL REFERENCES wh_finance_period(id),warehouse_id VARCHAR(64) NOT NULL REFERENCES wh_record(id))");
        OrderWorkSchema.create(jdbc);
        com.ruoyi.warehouse.stocktake.StocktakeSchema.create(jdbc);
        com.ruoyi.warehouse.finance.StorageSchema.create(jdbc);
        scope = mock(WarehouseScope.class); when(scope.actor()).thenReturn(7L); when(scope.granted(anyString(), anyString())).thenReturn(true); when(scope.predicate(eq("orders"), anyList())).thenReturn("1=1");
        var records = new WarehouseRepository(jdbc, scope); inventory = new InventoryService(records, scope); service = new OrderService(records, scope, new OrderStock(records, scope), new FinancePeriodService(records, scope));
        client = map("shortName", "Synthetic", "active", true, "firstPick", 2, "additionalPick", "0.5", "packagingFee", 1, "orderAdminFee", 2, "currency", "GBP", "pickingLogic", "FIFO");
        record("client", "clients", client); record("product", "products", map("client", "client", "sku", "SYNTHETIC", "name", "Synthetic product", "active", true));
        record("warehouse", "warehouses", map("name", "Warehouse", "active", true)); record("type", "locationTypes", map("name", "Pick", "active", true, "pickPriority", 1, "maxUnits", 0));
        record("location", "locations", map("name", "A-1", "warehouse", "warehouse", "type", "Pick", "active", true));
    }

    @Test void completeManualOrderPostsExactlyOneGoldenChargeAndConsumesReservations() {
        receive("product", 5, "location", ""); var order = save(null, input(3)); String id = text(order, "id");
        assertEquals("NEW", order.get("status")); assertEquals("MANUAL", order.get("source"));
        assertEquals("ALLOCATED", action(id, "allocate").get("status")); assertEquals(3, scalar("SELECT SUM(reserved) FROM wh_inventory")); assertEquals(5, scalar("SELECT SUM(on_hand) FROM wh_inventory"));
        var allocation = WarehouseJson.maps(service.get(id).get("allocations")).get(0);
        assertEquals("SYNTHETIC", allocation.get("sku")); assertEquals("Synthetic product", allocation.get("productName")); assertEquals("Warehouse", allocation.get("warehouseName")); assertEquals("A-1", allocation.get("locationName"));
        assertEquals("PICKED", action(id, "pick").get("status"));
        var body = actionInput("despatch"); body.put("tracking", "MANUAL-REFERENCE"); var despatched = act(id, body);
        assertEquals("DESPATCHED", despatched.get("status")); assertEquals("MANUAL-REFERENCE", despatched.get("tracking")); assertEquals("CONSUMED", WarehouseJson.maps(despatched.get("allocations")).get(0).get("state"));
        assertEquals(2, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(0, scalar("SELECT SUM(reserved) FROM wh_inventory"));
        assertEquals(2, scalar("SELECT SUM(remaining_quantity) FROM wh_stock_layer")); assertEquals(0, scalar("SELECT SUM(reserved_quantity) FROM wh_stock_layer"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
        var charge = WarehouseJson.map(jdbc.queryForObject("SELECT business_json FROM wh_record WHERE kind='charges'", String.class));
        money("6.00", charge.get("amount")); assertEquals("order:" + id, charge.get("source"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM wh_order_billing")); assertFalse(despatched.containsKey("source_snapshot")); assertFalse(despatched.containsKey("amount"));
        assertEquals(WarehouseJson.write(despatched), WarehouseJson.write(act(id, body)));
        assertThrows(WarehouseException.class, () -> action(id, "despatch")); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
        assertEquals(2, scalar("SELECT SUM(quantity_delta) FROM wh_ledger")); assertEquals(0, scalar("SELECT SUM(reserved_delta) FROM wh_ledger"));
    }

    @Test void createAndEditAreIdempotentAndReferenceIdentityIsUnique() {
        var input = input(2); var order = save(null, input); assertEquals(text(order, "id"), text(save(null, input), "id"));
        var duplicate = new LinkedHashMap<>(input); duplicate.put("requestId", UUID.randomUUID().toString()); duplicate.put("reference", text(input, "reference").toUpperCase(Locale.ROOT));
        assertThrows(RuntimeException.class, () -> save(null, duplicate)); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
        var update = new LinkedHashMap<>(input); update.put("requestId", UUID.randomUUID().toString()); update.put("_version", order.get("_version")); update.put("name", "Changed recipient");
        var saved = save(text(order, "id"), update); assertEquals("Changed recipient", saved.get("name")); assertEquals(WarehouseJson.write(saved), WarehouseJson.write(save(text(order, "id"), update)));
        update.put("requestId", UUID.randomUUID().toString()); assertThrows(WarehouseException.class, () -> save(text(order, "id"), update));
        var protectedInput = input(1); protectedInput.put("status", "DESPATCHED"); assertThrows(WarehouseException.class, () -> save(null, protectedInput));
    }

    @Test void cancelReleasesReservationsWithoutIncreasingOnhandEvenWhenClientStopped() {
        receive("product", 5, "location", ""); String id = text(save(null, input(3)), "id"); action(id, "allocate");
        client.put("onStop", true); update("client", client);
        var cancelled = action(id, "cancel"); assertEquals("CANCELLED", cancelled.get("status")); assertEquals("RELEASED", WarehouseJson.maps(cancelled.get("allocations")).get(0).get("state"));
        assertEquals(5, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(0, scalar("SELECT SUM(reserved) FROM wh_inventory")); assertEquals(5, scalar("SELECT SUM(remaining_quantity) FROM wh_stock_layer"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
    }

    @Test void failedMultiProductAllocationRollsBackAllEarlierReservations() {
        record("product2", "products", map("client", "client", "sku", "SECOND")); receive("product", 5, "location", "");
        var input = input(3); input.put("lines", List.of(map("product", "product", "quantity", 3), map("product", "product2", "quantity", 2))); String id = text(save(null, input), "id");
        var allocation = actionInput("allocate"); assertThrows(WarehouseException.class, () -> act(id, allocation));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_order_allocation")); assertEquals(0, scalar("SELECT SUM(reserved) FROM wh_inventory")); assertEquals("NEW", service.get(id).get("status"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='" + allocation.get("requestId") + "'"));
    }

    @Test void expiryBlockingIsCheckedAgainAtPickAndCancellationCanStillReleaseIt() {
        update("product", map("client", "client", "sku", "SYNTHETIC", "hasExpiry", true, "expiryPolicy", "Warn"));
        receive("product", 3, "location", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()); String id = text(save(null, input(2)), "id"); var allocated = action(id, "allocate");
        assertTrue(text(WarehouseJson.maps(allocated.get("allocations")).get(0), "expiryWarning").startsWith("expired stock"));
        update("product", map("client", "client", "sku", "SYNTHETIC", "hasExpiry", true, "expiryPolicy", "BlockExpired"));
        assertThrows(WarehouseException.class, () -> action(id, "pick")); assertEquals("ALLOCATED", service.get(id).get("status"));
        action(id, "cancel"); assertEquals(0, scalar("SELECT SUM(reserved) FROM wh_inventory"));
        String second = text(save(null, input(1)), "id"); assertThrows(WarehouseException.class, () -> action(second, "allocate"));
    }

    @Test void expiryTodayRemainsValidAndMissingRequiredDatesAreNotAllocated() {
        client.put("expiryPolicy", "BlockExpired"); update("client", client);
        update("product", map("client", "client", "sku", "SYNTHETIC", "hasExpiry", true));
        receive("product", 2, "location", LocalDate.now(ZoneOffset.UTC).toString()); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick"); action(id, "despatch");
        assertEquals(0, scalar("SELECT SUM(on_hand) FROM wh_inventory"));
        assertFalse(OrderStock.eligibleLot(map("hasExpiry", true), client, new OrderStock.Pool("pool", "client", "product", "warehouse", "location", "", "", "", 1, 0)));
    }

    @Test void fifoSelectsOriginalLayerAgeAcrossLocationsAndPreservesItInAllocation() {
        record("location2", "locations", map("name", "B-1", "warehouse", "warehouse", "type", "Pick", "active", true));
        receive("product", 3, "location2", ""); Timestamp old = Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")); jdbc.update("UPDATE wh_stock_layer SET received_at=?", old);
        receive("product", 3, "location", ""); update("product", map("client", "client", "sku", "SYNTHETIC", "defaultLocation", "location"));
        String id = text(save(null, input(2)), "id"); var allocated = action(id, "allocate"); var row = WarehouseJson.maps(allocated.get("allocations")).get(0);
        assertEquals("location2", row.get("location")); assertEquals(old.toInstant().toString(), row.get("receivedAt"));
    }

    @Test void holdingTransitionsAndEditingCannotBypassAllocatedWork() {
        receive("product", 3, "location", ""); var input = input(2); var order = save(null, input); String id = text(order, "id");
        assertEquals("HOLDING", action(id, "hold").get("status")); assertThrows(WarehouseException.class, () -> action(id, "allocate")); assertEquals("NEW", action(id, "release").get("status"));
        var allocated = action(id, "allocate"); var edit = new LinkedHashMap<>(input); edit.put("requestId", UUID.randomUUID().toString()); edit.put("_version", allocated.get("_version"));
        assertThrows(WarehouseException.class, () -> save(id, edit));
        var body = actionInput("pick"); act(id, body); body.put("action", "despatch"); assertThrows(WarehouseException.class, () -> act(id, body));
    }

    @Test void requiredAdvancedWorkflowsAndForeignProductsAreExplicitlyBlocked() {
        for (String flag : List.of("requireRework", "infiniteStock", "onStop")) { client.put(flag, true); update("client", client); assertThrows(WarehouseException.class, () -> save(null, input(1))); client.remove(flag); }
        update("client", client);
        for (String flag : List.of("bundle", "dropship", "infiniteStock", "discontinued")) { update("product", map("client", "client", "sku", "SYNTHETIC", flag, true)); assertThrows(WarehouseException.class, () -> save(null, input(1))); }
        update("product", map("client", "other-client", "sku", "SYNTHETIC")); assertThrows(WarehouseException.class, () -> save(null, input(1)));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
    }

    @Test void periodOrderTierRegistersExactlyOneOrderAndChargesOnlyExtrasAtDespatch() {
        receive("product", 3, "location", ""); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick");
        record("period-tier", "orderTiers", map("client", "client", "active", true, "tiers", List.of(map("from", 1, "rate", 1))));
        var body = actionInput("despatch"); var first = act(id, body); assertEquals(WarehouseJson.write(first), WarehouseJson.write(act(id, body)));
        assertEquals("DESPATCHED", first.get("status")); assertEquals("TIER_PENDING", first.get("billingStatus"));
        assertEquals(1, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(0, scalar("SELECT SUM(reserved) FROM wh_inventory"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM wh_finance_period")); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_finance_tier_order"));
        var charge = WarehouseJson.map(jdbc.queryForObject("SELECT business_json FROM wh_record WHERE kind='charges'", String.class));
        assertEquals("order-extras:" + id, charge.get("source")); assertEquals("Additional", charge.get("type")); money("3", charge.get("amount"));
        var snapshot = WarehouseJson.map(jdbc.queryForObject("SELECT source_snapshot FROM wh_order_billing", String.class));
        assertTrue(snapshot.containsKey("period")); assertEquals(first.get("billingPeriodId"), jdbc.queryForObject("SELECT id FROM wh_finance_period", String.class));
    }

    @Test void channelOrderTierWinsAndLaterRuleEditsCannotReplaceTheFirstPeriodSnapshot() {
        record("client-tier", "orderTiers", map("client", "client", "basis", "Orders", "active", true, "tiers", List.of(map("from", 1, "rate", 9))));
        record("channel-tier", "channelTiers", map("client", "client", "channel", "Manual", "basis", "Orders", "active", true, "tiers", List.of(map("from", 1, "rate", 1))));
        receive("product", 102, "location", ""); String firstId = text(save(null, input(100)), "id"); action(firstId, "allocate"); action(firstId, "pick"); var first = action(firstId, "despatch");
        assertEquals("channel-tier", jdbc.queryForObject("SELECT rule_id FROM wh_finance_period", String.class));
        assertEquals(1, scalar("SELECT COUNT(*) FROM wh_finance_tier_order"));
        String originalSnapshot = jdbc.queryForObject("SELECT snapshot_json FROM wh_finance_period", String.class);
        update("channel-tier", map("client", "client", "channel", "Manual", "basis", "Orders", "active", true, "tiers", List.of(map("from", 1, "rate", 7))));
        String secondId = text(save(null, input(1)), "id"); action(secondId, "allocate"); action(secondId, "pick"); var second = action(secondId, "despatch");
        assertEquals(first.get("billingPeriodId"), second.get("billingPeriodId")); assertEquals(originalSnapshot, jdbc.queryForObject("SELECT snapshot_json FROM wh_finance_period", String.class));
        assertEquals(2, scalar("SELECT COUNT(*) FROM wh_finance_tier_order"));
        assertEquals(1, scalar("SELECT SUM(on_hand) FROM wh_inventory"));
    }

    @Test void periodMembershipAndExtrasRollBackTogetherWhenDespatchAuditFails() {
        record("period-tier", "orderTiers", map("client", "client", "basis", "Orders", "active", true, "tiers", List.of(map("from", 1, "rate", 1))));
        receive("product", 2, "location", ""); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick");
        jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_period_despatch CHECK(action_name<>'MANUAL_ORDER_DESPATCH')"); var body = actionInput("despatch");
        assertThrows(RuntimeException.class, () -> act(id, body));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_finance_period")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_finance_tier_order"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_order_billing")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
        assertEquals(2, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(2, scalar("SELECT SUM(reserved) FROM wh_inventory")); assertEquals("PICKED", service.get(id).get("status"));
        jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_period_despatch"); act(id, body);
        assertEquals(1, scalar("SELECT COUNT(*) FROM wh_finance_tier_order")); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_order_billing"));
    }

    @Test void periodRegistrationConflictCannotConsumeStockOrCreateAnExtraCharge() {
        record("period-tier", "orderTiers", map("client", "client", "basis", "Orders", "active", true, "tiers", List.of(map("from", 1, "rate", 1))));
        receive("product", 3, "location", ""); String firstId = text(save(null, input(1)), "id"); action(firstId, "allocate"); action(firstId, "pick"); action(firstId, "despatch");
        String secondId = text(save(null, input(1)), "id"); action(secondId, "allocate"); action(secondId, "pick");
        jdbc.update("UPDATE wh_finance_period SET settled_count=1"); assertThrows(WarehouseException.class, () -> action(secondId, "despatch"));
        assertEquals("PICKED", service.get(secondId).get("status")); assertEquals(2, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(1, scalar("SELECT SUM(reserved) FROM wh_inventory"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM wh_finance_tier_order")); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
    }

    @Test void despatchAuditFailureRollsBackTheChargeAndAllConsumption() {
        receive("product", 3, "location", ""); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick");
        jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_despatch CHECK(action_name<>'MANUAL_ORDER_DESPATCH')"); var body = actionInput("despatch"); assertThrows(RuntimeException.class, () -> act(id, body));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_order_billing")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
        assertEquals(3, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(2, scalar("SELECT SUM(reserved) FROM wh_inventory")); assertEquals("PICKED", service.get(id).get("status"));
        jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_despatch"); act(id, body); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_order_billing"));
    }

    @Test void oversizedChargeCannotDespatchStockOrWriteUninvoiceableFinancialEvidence() {
        client.put("firstPick", new BigDecimal("1e23")); update("client", client);
        receive("product", 2, "location", ""); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick");
        var request = actionInput("despatch"); long ledgerBefore = scalar("SELECT COUNT(*) FROM wh_ledger");
        assertThrows(WarehouseException.class, () -> act(id, request));
        assertEquals("PICKED", service.get(id).get("status")); assertEquals(2, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(2, scalar("SELECT SUM(reserved) FROM wh_inventory"));
        assertEquals(2, scalar("SELECT SUM(remaining_quantity) FROM wh_stock_layer")); assertEquals(2, scalar("SELECT SUM(reserved_quantity) FROM wh_stock_layer"));
        assertEquals(ledgerBefore, scalar("SELECT COUNT(*) FROM wh_ledger")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_order_billing"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_finance_period")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='" + request.get("requestId") + "'"));
        client.put("firstPick", 2); update("client", client);
        assertEquals("DESPATCHED", act(id, request).get("status")); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_order_billing")); assertEquals(0, scalar("SELECT SUM(on_hand) FROM wh_inventory"));
    }

    @Test void corruptedReservationEvidenceNeverConsumesAnotherOrdersStock() {
        receive("product", 5, "location", ""); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick");
        jdbc.update("UPDATE wh_order_allocation SET quantity=3 WHERE order_id=?", id);
        assertThrows(WarehouseException.class, () -> action(id, "despatch")); assertEquals(5, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_order_billing"));
    }

    @Test void competingOrdersCannotReserveTheSameLastUnits() throws Exception {
        receive("product", 3, "location", ""); String firstId = text(save(null, input(3)), "id"), secondId = text(save(null, input(3)), "id");
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Boolean> firstCall = () -> { start.await(); try { action(firstId, "allocate"); return true; } catch (WarehouseException e) { return false; } };
            Callable<Boolean> secondCall = () -> { start.await(); try { action(secondId, "allocate"); return true; } catch (WarehouseException e) { return false; } };
            var first = executor.submit(firstCall); var second = executor.submit(secondCall); start.countDown(); assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(3, scalar("SELECT SUM(reserved) FROM wh_inventory")); assertEquals(3, scalar("SELECT SUM(quantity) FROM wh_order_allocation WHERE state='RESERVED'"));
        } finally { executor.shutdownNow(); }
    }

    @Test void exponentialOrderValuesCannotBypassIntegerDigitLimits() {
        for (String value : List.of("1e1000", "1e1000000000", "1e24", "1e-1000")) {
            var body = input(1); body.put("orderValue", value);
            assertThrows(WarehouseException.class, () -> save(null, body));
        }
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_operation_request"));
        var valid = input(1); valid.put("orderValue", "1e23");
        money("100000000000000000000000", save(null, valid).get("orderValue"));
    }

    @Test void stoppedPermissionsAreRecheckedBeforeIdempotentReplay() {
        var input = input(1); save(null, input); when(scope.granted("CLIENT", "client")).thenReturn(false); assertThrows(WarehouseException.class, () -> save(null, input));
        assertEquals(1, scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));
    }

    @Test void pricingSnapshotContainsOnlyThisClientsRelevantWarehouseAndChannelRules() {
        record("other-client", "clients", map("shortName", "Other synthetic client"));
        record("foreign-price", "warehousePrices", map("client", "other-client", "warehouse", "warehouse", "firstPick", 99));
        record("other-warehouse-price", "warehousePrices", map("client", "client", "warehouse", "other-warehouse", "firstPick", 98));
        record("other-product-price", "warehousePrices", map("client", "client", "warehouse", "warehouse", "product", "other-product", "firstPick", 97));
        record("valid-price", "warehousePrices", map("client", "client", "warehouse", "warehouse", "product", "product", "firstPick", 7, "additionalPick", 0));
        record("foreign-channel", "channelTiers", map("client", "other-client", "channel", "Manual", "basis", "Units", "tiers", List.of(map("from", 1, "rate", 99))));
        record("other-channel", "channelTiers", map("client", "client", "channel", "Shopify", "basis", "Units", "tiers", List.of(map("from", 1, "rate", 98))));
        record("own-disabled-channel", "channelTiers", map("client", "client", "channel", "Manual", "active", false, "basis", "Units", "tiers", List.of(map("from", 1, "rate", 99))));
        receive("product", 3, "location", ""); String id = text(save(null, input(3)), "id"); action(id, "allocate"); action(id, "pick"); action(id, "despatch");
        var snapshot = WarehouseJson.map(jdbc.queryForObject("SELECT source_snapshot FROM wh_order_billing WHERE order_id=?", String.class, id));
        @SuppressWarnings("unchecked") var context = (Map<String, Object>) snapshot.get("context");
        assertEquals(List.of("valid-price"), WarehouseJson.maps(context.get("overrides")).stream().map(row -> text(row, "id")).toList());
        assertEquals(List.of("own-disabled-channel"), WarehouseJson.maps(context.get("channelTiers")).stream().map(row -> text(row, "id")).toList());
        assertFalse(WarehouseJson.write(snapshot).contains("foreign-price")); assertFalse(WarehouseJson.write(snapshot).contains("other-client"));
        money("10.00", WarehouseJson.map(jdbc.queryForObject("SELECT quote_json FROM wh_order_billing WHERE order_id=?", String.class, id)).get("amount"));
    }

    @Test void discontinueZeroUsesAllWarehousesAndDoesNotDiscontinueRemainingStock() {
        client.put("discontinueZero", true); update("client", client);
        record("warehouse2", "warehouses", map("name", "Second", "active", true)); record("location2", "locations", map("name", "B-1", "warehouse", "warehouse2", "type", "Pick", "active", true));
        tx.execute(status -> inventory.move(map("requestId", UUID.randomUUID().toString(), "action", "add", "product", "product", "warehouse", "warehouse2", "location", "location2", "quantity", 1, "reason", "Synthetic second warehouse receipt")));
        receive("product", 2, "location", ""); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick"); action(id, "despatch");
        assertFalse(Boolean.TRUE.equals(WarehouseJson.map(jdbc.queryForObject("SELECT business_json FROM wh_record WHERE id='product'", String.class)).get("discontinued")));
        assertEquals(0, scalar("SELECT COUNT(*) FROM wh_audit WHERE action_name='DISCONTINUE_ZERO_STOCK'"));
        assertEquals(1, scalar("SELECT SUM(on_hand) FROM wh_inventory WHERE product_id='product'"));
    }

    @Test void discontinueZeroRollsBackWithDespatchAndAReplayedRequestCannotUpdateTwice() {
        client.put("discontinueZero", true); update("client", client);
        receive("product", 2, "location", ""); String id = text(save(null, input(2)), "id"); action(id, "allocate"); action(id, "pick");
        jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_discontinued_despatch CHECK(action_name<>'MANUAL_ORDER_DESPATCH')"); var body = actionInput("despatch");
        assertThrows(RuntimeException.class, () -> act(id, body));
        assertEquals(1, scalar("SELECT revision FROM wh_record WHERE id='product'")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_audit WHERE action_name='DISCONTINUE_ZERO_STOCK'"));
        assertEquals(2, scalar("SELECT SUM(on_hand) FROM wh_inventory")); assertEquals(0, scalar("SELECT COUNT(*) FROM wh_order_billing"));
        jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_discontinued_despatch"); var first = act(id, body); assertEquals(WarehouseJson.write(first), WarehouseJson.write(act(id, body)));
        assertEquals(true, WarehouseJson.map(jdbc.queryForObject("SELECT business_json FROM wh_record WHERE id='product'", String.class)).get("discontinued"));
        assertEquals(2, scalar("SELECT revision FROM wh_record WHERE id='product'")); assertEquals(1, scalar("SELECT COUNT(*) FROM wh_audit WHERE action_name='DISCONTINUE_ZERO_STOCK'"));
    }

    Map<String, Object> input(long quantity) { return map("requestId", UUID.randomUUID().toString(), "client", "client", "warehouse", "warehouse", "reference", "manual-" + UUID.randomUUID(), "lines", List.of(map("product", "product", "quantity", quantity)), "name", "Synthetic recipient", "address1", "1 Example Street", "town", "Example", "postcode", "TEST 1", "country", "UNITED KINGDOM"); }
    Map<String, Object> save(String id, Map<String, Object> input) { return tx.execute(status -> service.save(id, input)); }
    Map<String, Object> action(String id, String action) { return act(id, actionInput(action)); }
    Map<String, Object> act(String id, Map<String, Object> body) { return tx.execute(status -> service.action(id, body)); }
    Map<String, Object> actionInput(String action) { return map("requestId", UUID.randomUUID().toString(), "action", action); }
    void receive(String product, long quantity, String location, String expiry) { tx.execute(status -> inventory.move(map("requestId", UUID.randomUUID().toString(), "action", "add", "product", product, "warehouse", "warehouse", "location", location, "quantity", quantity, "reason", "Synthetic fixture receipt", "expiry", expiry))); }
    long scalar(String sql) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class)); }
    void record(String id, String kind, Map<String, Object> data) { jdbc.update("INSERT INTO wh_record(id,kind,client_id,warehouse_id,business_json,display_name,natural_key) VALUES(?,?,?,?,?,?,?)", id, kind, WarehouseJson.text(data, "client"), WarehouseJson.text(data, "warehouse"), WarehouseJson.write(data), data.getOrDefault("sku", data.getOrDefault("name", id)), id); }
    void update(String id, Map<String, Object> data) { jdbc.update("UPDATE wh_record SET business_json=? WHERE id=?", WarehouseJson.write(data), id); }
    static Map<String, Object> map(Object... values) { Map<String, Object> out = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]); return out; }
    static String text(Map<String, Object> value, String key) { return WarehouseJson.text(value, key); }
    static void money(String expected, Object value) { assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(value.toString()))); }
}
