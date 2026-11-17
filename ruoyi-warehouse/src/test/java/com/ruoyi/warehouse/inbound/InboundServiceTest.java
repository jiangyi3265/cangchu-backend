package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.inventory.InventoryService;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboundServiceTest {
    JdbcTemplate jdbc; TransactionTemplate tx; WarehouseRepository records; WarehouseScope scope; InboundService service;
    Map<String,Object> client;
    @BeforeEach void database() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:asn_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE wh_record(id VARCHAR(64) PRIMARY KEY,kind VARCHAR(64),client_id VARCHAR(64) DEFAULT '',warehouse_id VARCHAR(64) DEFAULT '',scope_key VARCHAR(140) DEFAULT '',natural_key VARCHAR(191),display_name VARCHAR(255),business_json VARCHAR(1000000),revision BIGINT DEFAULT 1,created_by BIGINT DEFAULT 7,updated_by BIGINT DEFAULT 7,created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),UNIQUE(kind,scope_key,natural_key))");
        jdbc.execute("CREATE TABLE wh_reference(owner_id VARCHAR(64) REFERENCES wh_record(id),target_id VARCHAR(64) REFERENCES wh_record(id),field_name VARCHAR(64),PRIMARY KEY(owner_id,target_id,field_name))");
        jdbc.execute("CREATE TABLE wh_inventory(id VARCHAR(64) PRIMARY KEY,client_id VARCHAR(64) REFERENCES wh_record(id),product_id VARCHAR(64) REFERENCES wh_record(id),warehouse_id VARCHAR(64) REFERENCES wh_record(id),location_id VARCHAR(64) REFERENCES wh_record(id),batch_number VARCHAR(191),serial_number VARCHAR(191),expiry_date VARCHAR(10),on_hand BIGINT CHECK(on_hand>=0),reserved BIGINT CHECK(reserved>=0 AND reserved<=on_hand),revision BIGINT,UNIQUE(product_id,warehouse_id,location_id,batch_number,serial_number,expiry_date))");
        jdbc.execute("CREATE TABLE wh_stock_layer(id VARCHAR(64) PRIMARY KEY,inventory_id VARCHAR(64) REFERENCES wh_inventory(id),origin_id VARCHAR(64),received_at TIMESTAMP(6),remaining_quantity BIGINT CHECK(remaining_quantity>=0),reserved_quantity BIGINT CHECK(reserved_quantity>=0 AND reserved_quantity<=remaining_quantity))");
        jdbc.execute("CREATE TABLE wh_ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,operation_id VARCHAR(64),inventory_id VARCHAR(64) REFERENCES wh_inventory(id),layer_id VARCHAR(64) REFERENCES wh_stock_layer(id),quantity_delta BIGINT,reserved_delta BIGINT,reference_id VARCHAR(64),reason VARCHAR(500),actor_id BIGINT)");
        jdbc.execute("CREATE TABLE wh_operation_request(actor_id BIGINT,request_id VARCHAR(64),operation_type VARCHAR(64),request_hash CHAR(64),response_json VARCHAR(1000000),PRIMARY KEY(actor_id,request_id))");
        jdbc.execute("CREATE TABLE wh_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT,action_name VARCHAR(80),record_id VARCHAR(64),record_kind VARCHAR(64),before_json VARCHAR(1000000),after_json VARCHAR(1000000))");
        jdbc.execute("CREATE TABLE wh_finance_source(source_key VARCHAR(191) PRIMARY KEY,charge_id VARCHAR(64) UNIQUE REFERENCES wh_record(id),evidence_json VARCHAR(1000000))");
        jdbc.execute("CREATE TABLE wh_asn_receipt(id VARCHAR(64) PRIMARY KEY,asn_id VARCHAR(64) REFERENCES wh_record(id),actor_id BIGINT,received_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6))");
        jdbc.execute("CREATE TABLE wh_asn_receipt_line(id VARCHAR(64) PRIMARY KEY,receipt_id VARCHAR(64) REFERENCES wh_asn_receipt(id),product_id VARCHAR(64) REFERENCES wh_record(id),location_id VARCHAR(64) REFERENCES wh_record(id),inventory_id VARCHAR(64) REFERENCES wh_inventory(id),layer_id VARCHAR(64) UNIQUE REFERENCES wh_stock_layer(id),quantity BIGINT CHECK(quantity>0),batch_number VARCHAR(191),serial_number VARCHAR(191),expiry_date VARCHAR(10))");
        scope=mock(WarehouseScope.class);when(scope.actor()).thenReturn(7L);when(scope.granted(anyString(),anyString())).thenReturn(true);when(scope.predicate(eq("asns"),anyList())).thenReturn("1=1");
        com.ruoyi.warehouse.stocktake.StocktakeSchema.create(jdbc);
        com.ruoyi.warehouse.finance.StorageSchema.create(jdbc);
        AsnCustodySchema.create(jdbc);CorrectionSchema.create(jdbc);
        records=new WarehouseRepository(jdbc,scope);service=new InboundService(records,scope,new InventoryService(records,scope));
        client=map("shortName","Synthetic receiving","active",true,"currency","GBP","goodsInRate","0.5","goodsInSku",2,"goodsInCarton",3,"asnAdmin",4,"asnHour",5);
        record("client","clients",client);record("product","products",map("client","client","sku","ASN-SYNTHETIC","name","Test goods","active",true));
        record("warehouse","warehouses",map("name","Warehouse","active",true));record("type","locationTypes",map("name","Pick","active",true));record("location","locations",map("name","A-1","warehouse","warehouse","type","Pick","active",true));
    }
    @Test void partialReceiptsChargeUnitsEachTimeAndSkuOnceThenCloseChargesDeliveryAndLabourOnce() {
        var asn=create(5);String id=id(asn);var first=receipt(asn,2);var partial=receive(id,first);
        assertEquals("PARTIALLY_RECEIVED",partial.get("status"));assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));money("3.00",charges().get(0).get("amount"));
        var second=receipt(partial,3);var full=receive(id,second);assertEquals("RECEIVED",full.get("status"));money("4.50",sumCharges());
        assertEquals(WarehouseJson.write(full),WarehouseJson.write(receive(id,second)));assertEquals(2,number("SELECT COUNT(*) FROM wh_asn_receipt"));
        var closing=closure(full);var closed=close(id,closing);assertEquals("CLOSED",closed.get("status"));money("20.75",sumCharges());
        assertEquals(WarehouseJson.write(closed),WarehouseJson.write(close(id,closing)));assertEquals(3,charges().size());assertEquals(3,number("SELECT COUNT(*) FROM wh_finance_source"));
        assertEquals(5,number("SELECT SUM(quantity_delta) FROM wh_ledger"));assertEquals(5,number("SELECT SUM(remaining_quantity) FROM wh_stock_layer"));
        assertEquals(2,WarehouseJson.maps(closed.get("receipts")).size());assertFalse(WarehouseJson.write(closed).contains("goodsInRate"));assertFalse(closed.containsKey("amount"));
        assertThrows(WarehouseException.class,()->receive(id,receipt(closed,1)));
    }
    @Test void receiptSplitsAcrossLocationsAndLotsWithoutRepeatingSkuFee() {
        record("location2","locations",map("name","B-1","warehouse","warehouse","type","Pick"));var asn=create(4);
        var body=receipt(asn,1);body.put("lines",List.of(line(2,"location","LOT-A",""),line(2,"location2","LOT-B","")));
        var full=receive(id(asn),body);assertEquals("RECEIVED",full.get("status"));assertEquals(2,number("SELECT COUNT(*) FROM wh_inventory"));money("4.00",sumCharges());
        assertEquals(2,number("SELECT COUNT(*) FROM wh_asn_receipt_line"));assertEquals(1,number("SELECT COUNT(*) FROM wh_asn_receipt"));
    }
    @Test void missingPriceRollsBackAndExplicitZeroIsValid() {
        var asn=create(2);client.remove("goodsInRate");update("client",client);var body=receipt(asn,2);
        assertThrows(WarehouseException.class,()->receive(id(asn),body));assertEmptyReceipt();
        client.put("goodsInRate",0);client.put("goodsInSku",0);update("client",client);receive(id(asn),body);money("0.00",sumCharges());
    }
    @Test void capacityFailureInLaterLineRollsBackEarlierStockAndAllEvidence() {
        record("location2","locations",map("name","B-1","warehouse","warehouse","type","Pick","maxUnits",1));var asn=create(4);var body=receipt(asn,1);body.put("lines",List.of(line(2,"location","",""),line(2,"location2","","")));
        assertThrows(WarehouseException.class,()->receive(id(asn),body));assertEmptyReceipt();assertEquals("NEW",service.get(id(asn)).get("status"));
        assertEquals(0,number("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='"+body.get("requestId")+"'"));
    }
    @Test void auditFailureRollsBackStockFeesAndReceiptAndSameRequestCanRetry() {
        var asn=create(2);var body=receipt(asn,2);jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_asn_audit CHECK(action_name<>'ASN_RECEIVE')");
        assertThrows(RuntimeException.class,()->receive(id(asn),body));assertEmptyReceipt();
        jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_asn_audit");assertEquals("RECEIVED",receive(id(asn),body).get("status"));
    }
    @Test void duplicateSerialEvenWithinOneRequestRollsBackEverything() {
        update("product",map("client","client","sku","ASN-SYNTHETIC","hasSerial",true,"logSerialInbound","Required"));var asn=create(2);var body=receipt(asn,1);body.put("lines",List.of(line(1,"location","","SERIAL-A"),line(1,"location","","SERIAL-A")));
        assertThrows(WarehouseException.class,()->receive(id(asn),body));assertEmptyReceipt();
        body.put("lines",List.of(line(1,"location","","SERIAL-A"),line(1,"location","","SERIAL-B")));assertEquals("RECEIVED",receive(id(asn),body).get("status"));
    }
    @Test void trackingAndExpiryAreValidatedBeforeReceipt() {
        update("product",map("client","client","sku","ASN-SYNTHETIC","hasBatch",true,"logBatchInbound","Required","hasExpiry",true,"logExpiryInbound","Required"));var asn=create(1);var body=receipt(asn,1);
        assertThrows(WarehouseException.class,()->receive(id(asn),body));var line=line(1,"location","BATCH-A","");line.put("expiry","2025-02-30");body.put("lines",List.of(line));assertThrows(WarehouseException.class,()->receive(id(asn),body));
        line.put("expiry","2000-01-01");assertThrows(WarehouseException.class,()->receive(id(asn),body));assertEmptyReceipt();
        line.put("expiry","2099-12-31");assertEquals("RECEIVED",receive(id(asn),body).get("status"));
    }
    @Test void overReceiptAndUnexpectedProductAreRejectedWithoutStock() {
        var asn=create(2);assertThrows(WarehouseException.class,()->receive(id(asn),receipt(asn,3)));var body=receipt(asn,1);WarehouseJson.maps(body.get("lines")).get(0).put("product","another");assertThrows(WarehouseException.class,()->receive(id(asn),body));assertEmptyReceipt();
    }
    @Test void shortfallNeedsAdministratorAndExplicitReasonThenIsRecorded() {
        var asn=create(5);var partial=receive(id(asn),receipt(asn,2));var body=closure(partial);
        doThrow(WarehouseException.forbidden()).when(scope).requireAdministrator();assertThrows(WarehouseException.class,()->close(id(asn),body));assertEquals(1,charges().size());
        doNothing().when(scope).requireAdministrator();assertThrows(WarehouseException.class,()->close(id(asn),body));body.put("approveShortfall",true);body.put("reason","Supplier confirmed three units missing");
        var closed=close(id(asn),body);assertEquals(3L,((Number)((Number)((Map<?,?>)closed.get("shortfall")).get("product")).longValue()).longValue());assertEquals(7L,((Number)closed.get("shortfallApprovedBy")).longValue());assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void cancelOnlyBeforeReceiptAndForecastEditingHasOptimisticVersion() {
        var asn=create(2);var input=forecast(3);input.put("reference",asn.get("reference"));input.put("_version",asn.get("_version"));var changed=tx.execute(s->service.save(id(asn),input));
        input.put("requestId",UUID.randomUUID().toString());assertThrows(WarehouseException.class,()->tx.execute(s->service.save(id(asn),input)));
        var cancelled=tx.execute(s->service.cancel(id(asn),map("requestId",UUID.randomUUID().toString(),"_version",changed.get("_version"),"reason","Supplier cancelled")));assertEquals("CANCELLED",cancelled.get("status"));assertThrows(WarehouseException.class,()->receive(id(asn),receipt(cancelled,1)));
        var another=create(2);var partial=receive(id(another),receipt(another,1));assertThrows(WarehouseException.class,()->tx.execute(s->service.cancel(id(another),map("requestId",UUID.randomUUID().toString(),"_version",partial.get("_version"),"reason","Not allowed"))));
    }
    @Test void revokedScopeAlsoRejectsCompletedReplay() {
        var asn=create(1);var body=receipt(asn,1);receive(id(asn),body);doThrow(WarehouseException.forbidden()).when(scope).assertRecord(eq("asns"),anyMap());assertThrows(WarehouseException.class,()->receive(id(asn),body));assertEquals(1,charges().size());
    }
    @Test void wrongLocationClientWarehouseQuarantineAndInactiveLocationAreRejected() {
        var asn=create(1);for(var changed:List.of(map("warehouse","other","type","Pick"),map("warehouse","warehouse","type","Pick","client","other"),map("warehouse","warehouse","type","Quarantine"),map("warehouse","warehouse","type","Pick","custodyOnly",true),map("warehouse","warehouse","type","Pick","active",false))){changed.put("name","A-1");update("location",changed);assertThrows(WarehouseException.class,()->receive(id(asn),receipt(asn,1)));assertEmptyReceipt();}
    }
    @Test void currencyChangeAndCrossClientProductAreRejected() {
        var asn=create(1);client.put("currency","EUR");update("client",client);assertThrows(WarehouseException.class,()->receive(id(asn),receipt(asn,1)));assertEmptyReceipt();
        update("product",map("client","other","sku","ASN-SYNTHETIC"));assertThrows(WarehouseException.class,()->create(1));
    }
    @Test void concurrentIdenticalRequestProducesOneReceiptAndOneCharge() throws Exception {
        var asn=create(2);var body=receipt(asn,2);var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return receive(id(asn),body);});var b=pool.submit(()->{start.await();return receive(id(asn),body);});start.countDown();assertEquals(WarehouseJson.write(a.get(10,TimeUnit.SECONDS)),WarehouseJson.write(b.get(10,TimeUnit.SECONDS)));}finally{pool.shutdownNow();}
        assertEquals(1,number("SELECT COUNT(*) FROM wh_asn_receipt"));assertEquals(1,charges().size());assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void staleVersionCannotReceiveAgainAndRequestIdentityCannotChangePayload() {
        var asn=create(3);var body=receipt(asn,1);receive(id(asn),body);assertThrows(WarehouseException.class,()->receive(id(asn),receipt(asn,1)));WarehouseJson.maps(body.get("lines")).get(0).put("quantity",2);assertThrows(WarehouseException.class,()->receive(id(asn),body));assertEquals(1,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void inputCannotInjectStockPricesOrWorkflowStatusAndReferenceIsUnique() {
        var input=forecast(2);input.put("status","CLOSED");assertThrows(WarehouseException.class,()->tx.execute(s->service.save(null,input)));input.remove("status");var saved=tx.execute(s->service.save(null,input));input.put("requestId",UUID.randomUUID().toString());input.put("reference",text(saved,"reference").toUpperCase(Locale.ROOT));assertThrows(RuntimeException.class,()->tx.execute(s->service.save(null,input)));
        var body=receipt(saved,1);body.put("amount",0);assertThrows(WarehouseException.class,()->receive(id(saved),body));assertEmptyReceipt();
    }
    @Test void productNoGoodsInExemptsUnitAndSkuChargesButNotDeliveryCharges() {
        update("product",map("client","client","sku","ASN-SYNTHETIC","noGoodsIn",true));client.remove("goodsInRate");client.remove("goodsInSku");update("client",client);var asn=create(1);var full=receive(id(asn),receipt(asn,1));money("0",sumCharges());close(id(asn),closure(full));money("16.25",sumCharges());
    }
    @Test void storedRateEvidenceDoesNotChangeWhenClientPriceChanges() {
        var asn=create(2);receive(id(asn),receipt(asn,1));String before=jdbc.queryForObject("SELECT evidence_json FROM wh_finance_source",String.class);client.put("goodsInRate",9);update("client",client);assertEquals(before,jdbc.queryForObject("SELECT evidence_json FROM wh_finance_source",String.class));money("2.50",sumCharges());
    }
    @Test void custodyStagingIsUnavailableAndPutawayPreservesAgeAndChargesOnce() {
        custodyLocation();var asn=stage(create(4),4);String id=id(asn),task=task(asn);
        assertEquals(4,number("SELECT SUM(on_hand) FROM wh_inventory"));assertTrue(charges().isEmpty());assertTrue(InboundService.received(asn).isEmpty());
        when(scope.inventoryPredicate(anyList())).thenReturn("1=1");var inventory=new InventoryService(records,scope);
        assertEquals(0L,WarehouseJson.maps(inventory.list(1,20,"","","","").get("rows")).get(0).get("available"));
        String origin=jdbc.queryForObject("SELECT layer_id FROM wh_asn_custody",String.class);var age=jdbc.queryForObject("SELECT received_at FROM wh_stock_layer WHERE id=?",java.sql.Timestamp.class,origin);
        asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"putaway",map("task",task,"quantity",2,"location","location"));money("3",sumCharges());
        assertEquals(2,number("SELECT on_hand FROM wh_inventory WHERE location_id='custody'"));assertEquals(2,number("SELECT on_hand FROM wh_inventory WHERE location_id='location'"));
        var request=actionBody(asn,map("task",task,"quantity",2,"location","location"));var complete=custodyAction(id,"putaway",request);
        assertEquals(WarehouseJson.write(complete),WarehouseJson.write(custodyAction(id,"putaway",request)));money("4",sumCharges());assertEquals(2,charges().size());assertEquals("RECEIVED",complete.get("status"));
        assertEquals(0L,((Number)complete.get("custodyPending")).longValue());assertEquals(1,number("SELECT COUNT(DISTINCT origin_id) FROM wh_stock_layer"));assertEquals(age,jdbc.queryForObject("SELECT MAX(received_at) FROM wh_stock_layer",java.sql.Timestamp.class));assertEquals(4,number("SELECT SUM(quantity_delta) FROM wh_ledger"));
        assertEquals(0,number("SELECT remaining_quantity FROM wh_stock_layer WHERE id='"+origin+"'"));
    }
    @Test void custodyFreezesForecastCancellationDirectReceiptAndClosure() {
        custodyLocation();var asn=stage(create(2),1);String id=id(asn);var update=forecast(3);update.put("_version",asn.get("_version"));update.put("reference",asn.get("reference"));
        assertThrows(WarehouseException.class,()->tx.execute(s->service.save(id,update)));
        assertThrows(WarehouseException.class,()->tx.execute(s->service.cancel(id,actionBody(asn,Map.of()))));
        assertThrows(WarehouseException.class,()->receive(id,receipt(asn,1)));
        assertThrows(WarehouseException.class,()->close(id,closure(asn)));assertEquals(1,number("SELECT SUM(on_hand) FROM wh_inventory"));assertTrue(charges().isEmpty());
    }
    @Test void overageApprovalDoesNotRewriteForecastOrCreateNegativeShortfall() {
        custodyLocation();var asn=stage(create(2),3);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));var awaiting=asn;
        assertThrows(WarehouseException.class,()->act(awaiting,"putaway",map("task",task,"quantity",3,"location","location")));
        asn=act(asn,"approveOverage",map("task",task));asn=act(asn,"putaway",map("task",task,"quantity",3,"location","location"));
        assertEquals(2,WarehouseJson.maps(asn.get("lines")).get(0).get("quantity"));assertEquals(1L,WarehouseJson.maps(asn.get("lines")).get(0).get("overage"));assertEquals("RECEIVED",asn.get("status"));money("3.50",sumCharges());
        var closed=close(id(asn),closure(asn));assertEquals(0L,((Number)((Map<?,?>)closed.get("shortfall")).get("product")).longValue());verify(scope,never()).requireAdministrator();
    }
    @Test void rejectedGoodsCanBeScrappedInPartsAndShortClosedWithoutSaleableInventory() {
        custodyLocation();var asn=stage(create(3),3);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","FAIL"));
        var failed=asn;assertThrows(WarehouseException.class,()->act(failed,"putaway",map("task",task,"quantity",1,"location","location")));
        asn=act(asn,"scrap",map("task",task,"quantity",1));var partial=asn;assertThrows(WarehouseException.class,()->act(partial,"inspect",map("task",task,"result","PASS")));
        asn=act(asn,"scrap",map("task",task,"quantity",2));assertEquals(0,number("SELECT SUM(on_hand) FROM wh_inventory"));assertTrue(charges().isEmpty());
        var closure=closure(asn);closure.put("approveShortfall",true);closure.put("reason","All goods physically damaged");var closed=close(id(asn),closure);assertEquals("CLOSED",closed.get("status"));assertEquals(3L,((Number)((Map<?,?>)closed.get("shortfall")).get("product")).longValue());money("16.25",sumCharges());verify(scope).requireAdministrator();
    }
    @Test void custodyPricingAndAuditFailuresRestoreSourceTaskAndAllFinancialEvidence() {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));var request=actionBody(asn,map("task",task,"quantity",2,"location","location"));String id=id(asn);
        client.remove("goodsInRate");update("client",client);assertThrows(WarehouseException.class,()->custodyAction(id,"putaway",request));assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(1,number("SELECT COUNT(*) FROM wh_inventory"));assertEquals(0,number("SELECT SUM(disposed_quantity) FROM wh_asn_custody"));assertTrue(charges().isEmpty());
        client.put("goodsInRate","0.5");update("client",client);jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_custody_audit CHECK(action_name<>'ASN_CUSTODY_PUTAWAY')");assertThrows(RuntimeException.class,()->custodyAction(id,"putaway",request));assertEquals(0,number("SELECT COUNT(*) FROM wh_asn_receipt"));assertEquals(0,number("SELECT COUNT(*) FROM wh_asn_custody_event WHERE action_name='putaway'"));
        jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_custody_audit");assertEquals("RECEIVED",custodyAction(id,"putaway",request).get("status"));money("3",sumCharges());
    }
    @Test void custodyCapacityFailureKeepsPhysicalSourceAndApprovedOverageUnchanged() {
        custodyLocation();var asn=stage(create(1),2);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"approveOverage",map("task",task));update("location",map("name","Full","warehouse","warehouse","type","Pick","maxUnits",1));var approved=asn;
        assertThrows(WarehouseException.class,()->act(approved,"putaway",map("task",task,"quantity",2,"location","location")));assertEquals(2,number("SELECT approved_extra FROM wh_asn_custody"));assertEquals(2,number("SELECT on_hand FROM wh_inventory"));assertTrue(charges().isEmpty());
    }
    @Test void expiredGoodsCanEnterCustodyButCannotBecomeAvailable() {
        custodyLocation();var asn=create(1);asn=act(asn,"stage",map("product","product","location","custody","quantity",1,"expiry","2000-01-01"));String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));var passed=asn;
        assertThrows(WarehouseException.class,()->act(passed,"putaway",map("task",task,"quantity",1,"location","location")));
        asn=act(asn,"inspect",map("task",task,"result","FAIL"));act(asn,"scrap",map("task",task,"quantity",1));assertEquals(0,number("SELECT SUM(on_hand) FROM wh_inventory"));assertTrue(charges().isEmpty());
    }
    @Test void custodyRequiresInspectionAndRejectsUnknownTaskOrInjectedApproval() {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);
        for(String action:List.of("putaway","scrap")){var args=map("task",task,"quantity",1);if(action.equals("putaway"))args.put("location","location");assertThrows(WarehouseException.class,()->act(asn,action,args));}
        assertThrows(WarehouseException.class,()->act(asn,"approveOverage",map("task",task)));
        assertThrows(WarehouseException.class,()->act(asn,"inspect",map("task","missing","result","PASS")));
        assertThrows(WarehouseException.class,()->act(asn,"inspect",map("task",task,"result","PASS","approved_extra",10)));
        assertThrows(WarehouseException.class,()->act(asn,"receive",Map.of()));assertTrue(charges().isEmpty());
    }
    @Test void custodyReceiptSerialUniquenessAndManualBypassAreBlocked() {
        custodyLocation();var asn=create(2);asn=act(asn,"stage",map("product","product","location","custody","quantity",1,"serial","CUSTODY-SERIAL"));var held=asn;
        assertThrows(WarehouseException.class,()->act(held,"stage",map("product","product","location","custody","quantity",1,"serial","CUSTODY-SERIAL")));
        var movement=map("requestId",UUID.randomUUID().toString(),"action","remove","product","product","warehouse","warehouse","location","custody","quantity",1,"serial","CUSTODY-SERIAL","reason","Bypass attempt");
        assertThrows(WarehouseException.class,()->tx.execute(s->new InventoryService(records,scope).move(movement)));assertEquals(1,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void custodyExactSourceMismatchBlocksEvenWhenOtherStockIsAvailable() {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));jdbc.update("UPDATE wh_stock_layer SET remaining_quantity=3");jdbc.update("UPDATE wh_inventory SET on_hand=3");var ready=asn;
        assertThrows(WarehouseException.class,()->act(ready,"putaway",map("task",task,"quantity",1,"location","location")));assertTrue(charges().isEmpty());assertEquals(0,number("SELECT disposed_quantity FROM wh_asn_custody"));
    }
    @Test void reinspectionInvalidatesUnusedOverageApproval() {
        custodyLocation();var asn=stage(create(1),2);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"approveOverage",map("task",task));asn=act(asn,"inspect",map("task",task,"result","FAIL"));assertEquals(0,number("SELECT approved_extra FROM wh_asn_custody"));
        asn=act(asn,"inspect",map("task",task,"result","PASS"));var ready=asn;assertThrows(WarehouseException.class,()->act(ready,"putaway",map("task",task,"quantity",2,"location","location")));
    }
    @Test void concurrentCustodyPutawayRetryOnlyMovesAndBillsOnce() throws Exception {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));String id=id(asn);var input=actionBody(asn,map("task",task,"quantity",2,"location","location"));
        var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return custodyAction(id,"putaway",input);});var b=pool.submit(()->{start.await();return custodyAction(id,"putaway",input);});start.countDown();assertEquals(WarehouseJson.write(a.get(10,TimeUnit.SECONDS)),WarehouseJson.write(b.get(10,TimeUnit.SECONDS)));}finally{pool.shutdownNow();}
        money("3",sumCharges());assertEquals(1,number("SELECT COUNT(*) FROM wh_asn_receipt"));assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void furtherArrivalAfterFullReceiptNeedsCustodyApprovalAndCannotBypassClosureGuard() {
        custodyLocation();var asn=create(2);asn=receive(id(asn),receipt(asn,2));asn=stage(asn,1);String task=task(asn);var held=asn;
        assertThrows(WarehouseException.class,()->close(id(held),closure(held)));asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"approveOverage",map("task",task));asn=act(asn,"putaway",map("task",task,"quantity",1,"location","location"));
        money("3.50",sumCharges());assertEquals(0,number("SELECT approved_extra FROM wh_asn_custody"));assertEquals(3L,InboundService.received(asn).get("product"));assertEquals("CLOSED",close(id(asn),closure(asn)).get("status"));
    }
    @Test void directReceiptOfOtherProductFinishesAnAsnWithPreviouslyApprovedOverage() {
        custodyLocation();record("product2","products",map("client","client","sku","OTHER-SKU","active",true));var forecast=forecast(2);forecast.put("lines",List.of(map("product","product","quantity",2),map("product","product2","quantity",1)));var asn=tx.execute(s->service.save(null,forecast));
        asn=stage(asn,3);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"approveOverage",map("task",task));asn=act(asn,"putaway",map("task",task,"quantity",3,"location","location"));assertEquals("PARTIALLY_RECEIVED",asn.get("status"));
        var body=receipt(asn,1);body.put("lines",List.of(map("product","product2","quantity",1,"location","location")));asn=receive(id(asn),body);assertEquals("RECEIVED",asn.get("status"));money("6",sumCharges());assertEquals("CLOSED",close(id(asn),closure(asn)).get("status"));
    }
    @Test void directAndStagedReceiptsShareTheSameAcceptedQuantityLimit() {
        custodyLocation();record("product2","products",map("client","client","sku","OTHER-SKU","active",true));var forecast=forecast(9999998);forecast.put("lines",List.of(map("product","product","quantity",9999998),map("product","product2","quantity",2)));var asn=tx.execute(s->service.save(null,forecast));
        asn=stage(asn,9999999);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"approveOverage",map("task",task));asn=act(asn,"putaway",map("task",task,"quantity",9999999,"location","location"));String id=id(asn);
        var body=receipt(asn,2);body.put("lines",List.of(map("product","product2","quantity",2,"location","location")));assertThrows(WarehouseException.class,()->receive(id,body));assertEquals(9999999,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(1,charges().size());
    }
    @Test void custodyReservesLastReceiptForCompletionInsteadOfAllowingATrappedPartialTask() {
        custodyLocation();var asn=stage(create(2),2);String id=id(asn),task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));
        for(int i=0;i<249;i++)jdbc.update("INSERT INTO wh_asn_receipt(id,asn_id,actor_id) VALUES(?,?,7)","synthetic-prior-"+i,id);
        var ready=asn;assertThrows(WarehouseException.class,()->act(ready,"putaway",map("task",task,"quantity",1,"location","location")));
        assertEquals(2,number("SELECT on_hand FROM wh_inventory"));asn=act(asn,"putaway",map("task",task,"quantity",2,"location","location"));assertEquals("RECEIVED",asn.get("status"));assertEquals(250,number("SELECT COUNT(*) FROM wh_asn_receipt"));
        var complete=asn;assertThrows(WarehouseException.class,()->stage(complete,1));assertEquals(1,number("SELECT COUNT(*) FROM wh_asn_custody"));
    }
    @Test void custodyEventLimitPreservesACompletionPathAndRollsBackUnfinishableSplits() {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"approveOverage",map("task",task));
        for(int i=0;i<3996;i++)jdbc.update("INSERT INTO wh_asn_custody_event(id,custody_id,action_name,quantity,reason,actor_id,result_json) VALUES(?,?,'inspect',0,'Synthetic prior event',7,'{}')","prior-event-"+i,task);
        var ready=asn;assertThrows(WarehouseException.class,()->act(ready,"putaway",map("task",task,"quantity",1,"location","location")));assertEquals(3999,number("SELECT COUNT(*) FROM wh_asn_custody_event"));assertTrue(charges().isEmpty());
        asn=act(asn,"putaway",map("task",task,"quantity",2,"location","location"));assertEquals("RECEIVED",asn.get("status"));assertEquals(4000,number("SELECT COUNT(*) FROM wh_asn_custody_event"));assertEquals(0,number("SELECT quantity-disposed_quantity FROM wh_asn_custody"));
    }
    @Test void stagedArrivalCannotExceedQuantityCapacityAfterDirectReceipts() {
        custodyLocation();var asn=create(9999999);asn=receive(id(asn),receipt(asn,9999999));var full=asn;
        assertThrows(WarehouseException.class,()->stage(full,2));assertEquals(0,number("SELECT COUNT(*) FROM wh_asn_custody"));assertEquals(9999999,number("SELECT SUM(on_hand) FROM wh_inventory"));
        asn=stage(asn,1);assertEquals(1L,((Number)asn.get("custodyPending")).longValue());
    }
    @Test void supplierApprovalReservesPhysicalGoodsUntilEvidencedPartialHandover() {
        custodyLocation();var asn=stage(create(5),5);String task=task(asn);asn=act(asn,"approveReturn",returnRequest(task,3));String approval=returnApproval(asn);
        assertEquals(5,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(3L,WarehouseJson.maps(asn.get("custodyTasks")).get(0).get("returnReserved"));assertTrue(charges().isEmpty());
        var ready=asn;assertThrows(WarehouseException.class,()->act(ready,"inspect",map("task",task,"result","PASS")));
        var args=handover(task,approval,2);asn=act(asn,"returnSupplier",args);assertEquals(3,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(2L,WarehouseJson.maps(asn.get("custodyTasks")).get(0).get("returned"));assertEquals(0L,WarehouseJson.maps(asn.get("custodyTasks")).get(0).get("scrapped"));
        var body=actionBody(asn,handover(task,approval,1));String id=id(asn);var delivered=custodyAction(id,"returnSupplier",body);assertEquals(WarehouseJson.write(delivered),WarehouseJson.write(custodyAction(id,"returnSupplier",body)));assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));
        // Returning uninspected units does not strand the remaining unreserved goods.
        asn=act(delivered,"inspect",map("task",task,"result","PASS"));asn=act(asn,"putaway",map("task",task,"quantity",2,"location","location"));money("3",sumCharges());
        var close=closure(asn);close.put("approveShortfall",true);close.put("reason","Three units returned to supplier");var closed=close(id,close);assertEquals(3,((Number)((Map<?,?>)closed.get("shortfall")).get("product")).intValue());
        assertEquals(3,number("SELECT handed_quantity FROM wh_asn_return_approval"));assertEquals(2,number("SELECT SUM(quantity_delta) FROM wh_ledger"));assertEquals(0,number("SELECT SUM(quantity-disposed_quantity) FROM wh_asn_custody"));
    }
    @Test void reservedSupplierUnitsCannotBePutAwayScrappedOrApprovedTwice() {
        custodyLocation();var asn=stage(create(4),4);String task=task(asn);asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"approveReturn",returnRequest(task,3));var reserved=asn;
        assertThrows(WarehouseException.class,()->act(reserved,"putaway",map("task",task,"quantity",2,"location","location")));assertThrows(WarehouseException.class,()->act(reserved,"approveReturn",returnRequest(task,2)));
        asn=act(asn,"putaway",map("task",task,"quantity",1,"location","location"));assertEquals(3,number("SELECT on_hand FROM wh_inventory WHERE location_id='custody'"));money("2.50",sumCharges());
        var approved=returnApproval(asn);asn=act(asn,"returnSupplier",handover(task,approved,3));assertEquals(3L,WarehouseJson.maps(asn.get("custodyTasks")).get(0).get("returned"));assertEquals(1,number("SELECT SUM(on_hand) FROM wh_inventory"));
        var failed=stage(create(2),2);String failedTask=task(failed);failed=act(failed,"inspect",map("task",failedTask,"result","FAIL"));failed=act(failed,"approveReturn",returnRequest(failedTask,2));var failReserved=failed;
        assertThrows(WarehouseException.class,()->act(failReserved,"scrap",map("task",failedTask,"quantity",1)));
    }
    @Test void cancellationReleasesOnlyUnhandedUnitsAndKeepsOriginalApprovalEvidence() {
        custodyLocation();var asn=stage(create(4),4);String task=task(asn);asn=act(asn,"approveReturn",returnRequest(task,4));String approval=returnApproval(asn),evidence=jdbc.queryForObject("SELECT evidence_json FROM wh_asn_return_approval",String.class);
        asn=act(asn,"returnSupplier",handover(task,approval,1));asn=act(asn,"cancelReturnApproval",map("task",task,"approval",approval));assertEquals(1,number("SELECT handed_quantity FROM wh_asn_return_approval"));assertEquals(3,number("SELECT cancelled_quantity FROM wh_asn_return_approval"));assertEquals(3,number("SELECT SUM(on_hand) FROM wh_inventory"));
        var cancelled=asn;assertThrows(WarehouseException.class,()->act(cancelled,"returnSupplier",handover(task,approval,1)));assertEquals(evidence,jdbc.queryForObject("SELECT evidence_json FROM wh_asn_return_approval",String.class));
        asn=act(asn,"inspect",map("task",task,"result","FAIL"));act(asn,"scrap",map("task",task,"quantity",3));assertEquals(0,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void supplierHandoverRequiresExactTaskApprovalEvidenceAndActualAcknowledgement() {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);asn=act(asn,"approveReturn",returnRequest(task,1));String approval=returnApproval(asn);var approved=asn;
        var missing=handover(task,approval,1);missing.remove("confirmHandover");assertThrows(WarehouseException.class,()->act(approved,"returnSupplier",missing));missing.put("confirmHandover","true");assertThrows(WarehouseException.class,()->act(approved,"returnSupplier",missing));
        var noEvidence=handover(task,approval,1);noEvidence.put("handoverReference"," ");assertThrows(WarehouseException.class,()->act(approved,"returnSupplier",noEvidence));
        assertThrows(WarehouseException.class,()->act(approved,"returnSupplier",handover(task,approval,2)));assertThrows(WarehouseException.class,()->act(approved,"returnSupplier",handover(task,"other-approval",1)));
        var unknownTarget=handover(task,approval,1);unknownTarget.put("recipient","Unapproved recipient");assertThrows(WarehouseException.class,()->act(approved,"returnSupplier",unknownTarget));assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(0,number("SELECT handed_quantity FROM wh_asn_return_approval"));
    }
    @Test void supplierHandoverAuditFailureRollsBackStockApprovalHistoryAndRetry() {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);asn=act(asn,"approveReturn",returnRequest(task,2));String approval=returnApproval(asn),id=id(asn);var body=actionBody(asn,handover(task,approval,2));
        jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_supplier_audit CHECK(action_name<>'ASN_CUSTODY_RETURNSUPPLIER')");assertThrows(RuntimeException.class,()->custodyAction(id,"returnSupplier",body));assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(0,number("SELECT handed_quantity FROM wh_asn_return_approval"));assertEquals(0,number("SELECT returned_quantity FROM wh_asn_custody"));assertEquals(0,number("SELECT COUNT(*) FROM wh_asn_custody_event WHERE action_name='returnSupplier'"));
        jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_supplier_audit");var handed=custodyAction(id,"returnSupplier",body);assertEquals(2L,WarehouseJson.maps(handed.get("custodyTasks")).get(0).get("returned"));assertTrue(charges().isEmpty());
    }
    @Test void concurrentSupplierHandoverRetryCreatesOneNegativeMovement() throws Exception {
        custodyLocation();var asn=stage(create(2),2);String task=task(asn);asn=act(asn,"approveReturn",returnRequest(task,2));String approval=returnApproval(asn),id=id(asn);var input=actionBody(asn,handover(task,approval,2));
        var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return custodyAction(id,"returnSupplier",input);});var b=pool.submit(()->{start.await();return custodyAction(id,"returnSupplier",input);});start.countDown();assertEquals(WarehouseJson.write(a.get(10,TimeUnit.SECONDS)),WarehouseJson.write(b.get(10,TimeUnit.SECONDS)));}finally{pool.shutdownNow();}
        assertEquals(0,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(1,number("SELECT COUNT(*) FROM wh_ledger WHERE quantity_delta<0"));assertEquals(2,number("SELECT handed_quantity FROM wh_asn_return_approval"));assertTrue(charges().isEmpty());
    }
    @Test void supplierApprovalReservesEnoughEventsForCancellationAndOrdinaryCompletion() {
        custodyLocation();var asn=stage(create(1),1);String task=task(asn);asn=act(asn,"approveReturn",returnRequest(task,1));String approval=returnApproval(asn);
        for(int i=0;i<3994;i++)jdbc.update("INSERT INTO wh_asn_custody_event(id,custody_id,action_name,quantity,reason,actor_id,result_json) VALUES(?,?,'inspect',0,'Synthetic prior event',7,'{}')","prior-event-"+i,task);
        asn=act(asn,"cancelReturnApproval",map("task",task,"approval",approval));assertEquals(3997,number("SELECT COUNT(*) FROM wh_asn_custody_event"));asn=act(asn,"inspect",map("task",task,"result","PASS"));asn=act(asn,"putaway",map("task",task,"quantity",1,"location","location"));assertEquals("RECEIVED",asn.get("status"));assertEquals(1,number("SELECT cancelled_quantity FROM wh_asn_return_approval"));
    }
    private Map<String,Object> returnRequest(String task,int q){return map("task",task,"quantity",q,"recipient","Synthetic supplier","destination","Synthetic destination, local test only","reference","RETURN-AUTH-TEST");}
    private Map<String,Object> handover(String task,String approval,int q){return map("task",task,"approval",approval,"quantity",q,"handoverReference","SIGNED-HANDOVER-TEST","confirmHandover",true);}
    private String returnApproval(Map<String,Object> asn){return String.valueOf(WarehouseJson.maps(asn.get("supplierReturnApprovals")).get(0).get("id"));}
    @Test void lateSupplementKeepsClosedOriginalAndAppliesExplicitGroupedSkuAndIncludedAdmin() {
        var parent=shortClosed(5,2);String original=WarehouseJson.write(records.get("asns",id(parent),false));var before=sumCharges();var body=supplementBody(parent,3,"included","group");var child=supplement(parent,body);
        assertEquals(original,WarehouseJson.write(records.get("asns",id(parent),false)));assertEquals(WarehouseJson.write(child),WarehouseJson.write(supplement(parent,body)));assertEquals(1,number("SELECT COUNT(*) FROM wh_asn_late_link"));
        assertDoesNotThrow(()->java.time.Instant.parse(String.valueOf(((Map<?,?>)child.get("lateSupplement")).get("createdAt"))));
        assertEquals(3L,((Number)WarehouseJson.maps(((Map<?,?>)child.get("lateSupplement")).get("lines")).get(0).get("matchedShortfall")).longValue());
        var part=receive(id(child),receipt(child,2));var full=receive(id(child),receipt(part,1));money("1.50",sumCharges().subtract(before));
        var closure=closure(full);closure.put("deliveryQuantity",1);closure.put("hours",0);close(id(child),closure);money("4.50",sumCharges().subtract(before));
        assertEquals(original,WarehouseJson.write(records.get("asns",id(parent),false)));assertEquals(0L,((Number)((Map<?,?>)service.get(id(parent)).get("lateRemaining")).get("product")).longValue());
        String evidence=jdbc.queryForObject("SELECT evidence_json FROM wh_finance_source WHERE source_key=?",String.class,"asn:"+id(child)+":closure");assertTrue(evidence.contains("configuredClient"));assertTrue(evidence.contains("included"));
        assertFalse(WarehouseJson.write(service.get(id(parent))).contains("billedSkus"));
    }
    @Test void supplementAllocationReservesOpenForecastAndCancellationReleasesOnlyItsMatch() {
        var parent=shortClosed(5,2);var a=supplement(parent,supplementBody(parent,2,"separate","separate"));var request=supplementBody(parent,2,"separate","separate");
        assertThrows(WarehouseException.class,()->supplement(parent,request));request.put("approveAdditional",true);var b=supplement(parent,request);var match=WarehouseJson.maps(((Map<?,?>)b.get("lateSupplement")).get("lines")).get(0);assertEquals(1L,((Number)match.get("matchedShortfall")).longValue());assertEquals(1L,((Number)match.get("additionalApproved")).longValue());
        tx.execute(s->service.cancel(id(a),map("requestId",UUID.randomUUID().toString(),"_version",a.get("_version"),"reason","Supplier cancelled this supplement")));
        assertEquals(2L,((Number)((Map<?,?>)service.get(id(parent)).get("lateRemaining")).get("product")).longValue());supplement(parent,supplementBody(parent,2,"separate","separate"));
        assertEquals(0L,((Number)((Map<?,?>)service.get(id(parent)).get("lateRemaining")).get("product")).longValue());
    }
    @Test void partialSupplementClosureReleasesUnreceivedMatchAndRejectsNestedOrEditedApproval() {
        var parent=shortClosed(5,2);var child=supplement(parent,supplementBody(parent,3,"separate","separate"));var edit=forecast(1);edit.put("_version",child.get("_version"));assertThrows(WarehouseException.class,()->tx.execute(s->service.save(id(child),edit)));
        var received=receive(id(child),receipt(child,1));var body=closure(received);body.put("approveShortfall",true);body.put("reason","Remaining two will arrive later");var closed=close(id(child),body);
        assertThrows(WarehouseException.class,()->supplement(closed,supplementBody(closed,1,"included","group")));supplement(parent,supplementBody(parent,2,"included","group"));
    }
    @Test void twoGroupedSupplementsChargeANewSkuOnlyOnceUnderConcurrentReceipt() throws Exception {
        custodyLocation();var staged=stage(create(2),2);var rejected=act(staged,"inspect",map("task",task(staged),"result","FAIL"));var scrapped=act(rejected,"scrap",map("task",task(rejected),"quantity",2));var closing=closure(scrapped);closing.put("approveShortfall",true);closing.put("reason","Original goods rejected");var parent=close(id(scrapped),closing);var initial=sumCharges();
        var a=supplement(parent,supplementBody(parent,1,"included","group"));var b=supplement(parent,supplementBody(parent,1,"included","group"));var pool=Executors.newFixedThreadPool(2);try{var results=pool.invokeAll(List.<Callable<Map<String,Object>>>of(()->receive(id(a),receipt(a,1)),()->receive(id(b),receipt(b,1))));for(var r:results)assertEquals("RECEIVED",r.get().get("status"));}finally{pool.shutdownNow();}
        money("3.00",sumCharges().subtract(initial));assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void separateSupplementKeepsIndependentSkuAndAdminCharges() {
        var parent=shortClosed(3,1);var before=sumCharges();var child=supplement(parent,supplementBody(parent,2,"separate","separate"));var full=receive(id(child),receipt(child,2));money("3.00",sumCharges().subtract(before));var body=closure(full);body.put("deliveryQuantity",1);body.put("hours",0);close(id(child),body);money("10.00",sumCharges().subtract(before));
    }
    @Test void supplementCustodyUsesSameGroupPriceAndMissingNewRateRollsBack() {
        custodyLocation();var parent=shortClosed(3,1);var child=supplement(parent,supplementBody(parent,2,"included","group"));var staged=stage(child,2);var passed=act(staged,"inspect",map("task",task(staged),"result","PASS"));var initial=sumCharges();
        client.remove("goodsInRate");client.remove("goodsInSku");update("client",client);var body=actionBody(passed,map("task",task(passed),"quantity",2,"location","location"));assertThrows(WarehouseException.class,()->custodyAction(id(child),"putaway",body));assertEquals(2L,((Number)WarehouseJson.maps(service.get(id(child)).get("custodyTasks")).get(0).get("remaining")).longValue());
        client.put("goodsInRate","0.5");update("client",client);var full=custodyAction(id(child),"putaway",body);assertEquals("RECEIVED",full.get("status"));money("1.00",sumCharges().subtract(initial));
    }
    @Test void concurrentSupplementCreationCannotDoubleClaimShortfall() throws Exception {
        var parent=shortClosed(2,1);var a=supplementBody(parent,1,"included","group");var b=supplementBody(parent,1,"included","group");var pool=Executors.newFixedThreadPool(2);try{var results=pool.invokeAll(List.<Callable<Boolean>>of(()->{try{supplement(parent,a);return true;}catch(WarehouseException error){return false;}},()->{try{supplement(parent,b);return true;}catch(WarehouseException error){return false;}}));assertEquals(1,results.stream().filter(r->{try{return r.get();}catch(Exception e){throw new RuntimeException(e);}}).count());}finally{pool.shutdownNow();}assertEquals(1,number("SELECT COUNT(*) FROM wh_asn_late_link"));
    }
    @Test void supplementApprovalAuditFailureRollsBackChildLinkAndRequestThenRetries() {
        var parent=shortClosed(2,1);var body=supplementBody(parent,1,"included","group");jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT fail_supplement CHECK(action_name<>'ASN_LATE_SUPPLEMENT')");assertThrows(RuntimeException.class,()->supplement(parent,body));assertEquals(0,number("SELECT COUNT(*) FROM wh_asn_late_link"));assertEquals(1,records.count("asns"));jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT fail_supplement");supplement(parent,body);assertEquals(2,records.count("asns"));
    }
    @Test void supplementPoliciesLifecycleScopeAndProtectedFieldsAreEnforced() {
        var open=create(2);assertThrows(WarehouseException.class,()->supplement(open,supplementBody(open,1,"included","group")));var parent=shortClosed(2,1);
        for(var change:List.of(map("adminPolicy",""),map("skuPolicy","guess"),map("approveAdditional","true"),map("client","other"),map("lines",List.of(map("product","unknown","quantity",1))))){var body=supplementBody(parent,1,"included","group");body.putAll(change);assertThrows(WarehouseException.class,()->supplement(parent,body));}
        var body=supplementBody(parent,1,"included","group");supplement(parent,body);doThrow(WarehouseException.forbidden()).when(scope).assertRecord(eq("asns"),anyMap());assertThrows(WarehouseException.class,()->supplement(parent,body));
    }
    @Test void unidentifiedArrivalHoldsPhysicalCapacityAndBlocksBypassWithoutCreatingSkuStock() {
        custodyLocation();var asn=unidentified(create(5),5);assertEquals(0,number("SELECT COUNT(*) FROM wh_inventory"));assertTrue(charges().isEmpty());assertEquals(5,number("SELECT quantity FROM wh_unidentified_occupancy WHERE location_id='custody'"));assertEquals(5L,((Number)asn.get("custodyPending")).longValue());
        assertThrows(WarehouseException.class,()->receive(id(asn),receipt(asn,5)));assertThrows(WarehouseException.class,()->close(id(asn),closure(asn)));assertThrows(WarehouseException.class,()->tx.execute(s->service.cancel(id(asn),actionBody(asn,Map.of()))));
        var edit=forecast(5);edit.put("_version",asn.get("_version"));assertThrows(WarehouseException.class,()->tx.execute(s->service.save(id(asn),edit)));assertTrue(records.stockExists("custody"));assertTrue(records.inventoryHistory("warehouse"));
    }
    @Test void identificationCanSplitPreservesObservedAgeAndChargesOnlyAfterInspectedAcceptance() {
        custodyLocation();var asn=unidentified(create(5),5);String arrival=arrival(asn);jdbc.update("UPDATE wh_asn_unidentified SET arrived_at=? WHERE id=?",java.sql.Timestamp.from(java.time.Instant.parse("2025-01-01T12:00:00Z")),arrival);
        var approved=unknown(asn,"unknownApprove",identity(asn,5,"product"));String approval=identityApproval(approved);var partial=unknown(approved,"unknownExecute",map("arrival",arrival,"approval",approval,"quantity",2));assertEquals(3L,((Number)WarehouseJson.maps(partial.get("unidentifiedArrivals")).get(0).get("remaining")).longValue());
        var full=unknown(partial,"unknownExecute",map("arrival",arrival,"approval",approval,"quantity",3));assertEquals(0,number("SELECT quantity FROM wh_unidentified_occupancy WHERE location_id='custody'"));assertTrue(charges().isEmpty());
        for(var task:WarehouseJson.maps(full.get("custodyTasks"))){String taskId=String.valueOf(task.get("id"));assertEquals(arrival,jdbc.queryForObject("SELECT origin_id FROM wh_stock_layer WHERE id=?",String.class,task.get("layerId")));assertEquals("2025-01-01T12:00:00Z",jdbc.queryForObject("SELECT received_at FROM wh_stock_layer WHERE id=?",(rs,n)->rs.getTimestamp(1).toInstant().toString(),task.get("layerId")));full=act(full,"inspect",map("task",taskId,"result","PASS"));full=act(full,"putaway",map("task",taskId,"quantity",task.get("quantity"),"location","location"));}
        assertEquals("RECEIVED",full.get("status"));money("4.50",sumCharges());assertEquals(5,number("SELECT SUM(quantity_delta) FROM wh_ledger"));
    }
    @Test void unexpectedIdentifiedProductKeepsOriginalForecastAndRequiresSeparateOverageAcceptance() {
        custodyLocation();record("unexpected","products",map("client","client","sku","EXTRA","name","Unexpected goods","active",true));var asn=unidentified(create(1),3);var original=records.get("asns",id(asn),false).get("lines");var approved=unknown(asn,"unknownApprove",identity(asn,3,"unexpected"));var identified=unknown(approved,"unknownExecute",map("arrival",arrival(asn),"approval",identityApproval(approved),"quantity",3));
        assertEquals(WarehouseJson.write(original),WarehouseJson.write(records.get("asns",id(asn),false).get("lines")));var line=WarehouseJson.maps(identified.get("lines")).stream().filter(r->"unexpected".equals(r.get("product"))).findFirst().orElseThrow();assertEquals(0L,((Number)line.get("quantity")).longValue());assertEquals(true,line.get("unexpected"));
        var passed=act(identified,"inspect",map("task",task(identified),"result","PASS"));assertThrows(WarehouseException.class,()->act(passed,"putaway",map("task",task(passed),"quantity",3,"location","location")));var excess=act(passed,"approveOverage",map("task",task(passed)));var full=act(excess,"putaway",map("task",task(excess),"quantity",3,"location","location"));assertEquals("PARTIALLY_RECEIVED",full.get("status"));assertEquals(3L,InboundService.received(full).get("unexpected"));
    }
    @Test void unidentifiedAndKnownArrivalsShareCapacityWithoutCountingIdentificationTwice() {
        custodyLocation();update("custody",map("name","Receiving hold","warehouse","warehouse","type","Quarantine","custodyOnly",true,"active",true,"maxUnits",3));var asn=unidentified(create(3),3);assertThrows(WarehouseException.class,()->stage(asn,1));var approved=unknown(asn,"unknownApprove",identity(asn,3,"product"));var identified=unknown(approved,"unknownExecute",map("arrival",arrival(asn),"approval",identityApproval(approved),"quantity",3));assertEquals(3,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(0,number("SELECT quantity FROM wh_unidentified_occupancy WHERE location_id='custody'"));assertThrows(WarehouseException.class,()->unidentified(identified,1));
    }
    @Test void unidentifiedReturnAndDisposalNeedReservedApprovalAndActualHandoverEvidence() {
        custodyLocation();var asn=unidentified(create(5),5);var approval=unknown(asn,"unknownApprove",map("arrival",arrival(asn),"decision","returnSupplier","quantity",2,"recipient","Supplier receiving","destination","Approved return dock","reference","AUTH-1"));String a=identityApproval(approval);var input=map("arrival",arrival(asn),"approval",a,"quantity",1,"reference","SIGNED-1","confirmPhysical",false);assertThrows(WarehouseException.class,()->unknown(approval,"unknownExecute",input));input.put("confirmPhysical",true);var body=actionBody(approval,input);var partial=unknownAction(id(asn),"unknownExecute",body);assertEquals(WarehouseJson.write(partial),WarehouseJson.write(unknownAction(id(asn),"unknownExecute",body)));
        var cancelled=unknown(partial,"unknownCancel",map("arrival",arrival(asn),"approval",a));var disposal=unknown(cancelled,"unknownApprove",map("arrival",arrival(asn),"decision","scrap","quantity",4,"recipient","Disposal contractor","destination","Approved waste collection","reference","SCRAP-AUTH"));String disposalId=WarehouseJson.maps(disposal.get("identificationApprovals")).stream().filter(r->"scrap".equals(r.get("decision"))).map(r->String.valueOf(r.get("id"))).findFirst().orElseThrow();var done=unknown(disposal,"unknownExecute",map("arrival",arrival(asn),"approval",disposalId,"quantity",4,"reference","WASTE-RECEIPT","confirmPhysical",true));
        var held=WarehouseJson.maps(done.get("unidentifiedArrivals")).get(0);assertEquals(1L,((Number)held.get("returned")).longValue());assertEquals(4L,((Number)held.get("scrapped")).longValue());assertEquals(0,number("SELECT SUM(quantity) FROM wh_unidentified_occupancy"));assertEquals(0,number("SELECT COUNT(*) FROM wh_inventory"));assertTrue(charges().isEmpty());var closing=closure(done);closing.put("approveShortfall",true);closing.put("reason","All unidentified goods physically removed");assertEquals("CLOSED",close(id(done),closing).get("status"));
    }
    @Test void identityRejectsChangedProductCrossClientAndObservedTrackingChanges() {
        custodyLocation();var asn=unknown(create(1),"unknownArrive",map("location","custody","quantity",1,"description","Unreadable item","barcode","OBS","evidenceReference","PHOTO-1","batch","OBS-BATCH"));var wrong=identity(asn,1,"product");wrong.put("batch","OTHER");assertThrows(WarehouseException.class,()->unknown(asn,"unknownApprove",wrong));wrong.put("batch","OBS-BATCH");var approved=unknown(asn,"unknownApprove",wrong);jdbc.update("UPDATE wh_record SET revision=revision+1 WHERE id='product'");assertThrows(WarehouseException.class,()->unknown(approved,"unknownExecute",map("arrival",arrival(asn),"approval",identityApproval(approved),"quantity",1)));assertEquals(0,number("SELECT COUNT(*) FROM wh_inventory"));
        var cancelled=unknown(approved,"unknownCancel",map("arrival",arrival(asn),"approval",identityApproval(approved)));record("foreign","products",map("client","foreign","sku","FOREIGN","active",true));var foreign=identity(cancelled,1,"foreign");foreign.put("batch","OBS-BATCH");assertThrows(WarehouseException.class,()->unknown(cancelled,"unknownApprove",foreign));
    }
    @Test void identityAuditFailureRollsBackCounterLayerTaskAndHistoryBeforeSameRequestRetry() {
        custodyLocation();var asn=unidentified(create(2),2);var approved=unknown(asn,"unknownApprove",identity(asn,2,"product"));var body=actionBody(approved,map("arrival",arrival(asn),"approval",identityApproval(approved),"quantity",2));jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_identity CHECK(action_name<>'ASN_UNKNOWNEXECUTE')");assertThrows(RuntimeException.class,()->unknownAction(id(asn),"unknownExecute",body));assertEquals(2,number("SELECT quantity FROM wh_unidentified_occupancy WHERE location_id='custody'"));assertEquals(0,number("SELECT COUNT(*) FROM wh_asn_custody"));assertEquals(0,number("SELECT COUNT(*) FROM wh_inventory"));jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_identity");unknownAction(id(asn),"unknownExecute",body);assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));
    }
    @Test void concurrentIdentityReplayCreatesOneTaskAndRevokedScopeStillBlocksReplay() throws Exception {
        custodyLocation();var asn=unidentified(create(1),1);var approved=unknown(asn,"unknownApprove",identity(asn,1,"product"));var body=actionBody(approved,map("arrival",arrival(asn),"approval",identityApproval(approved),"quantity",1));var pool=Executors.newFixedThreadPool(2);try{var results=pool.invokeAll(List.<Callable<Map<String,Object>>>of(()->unknownAction(id(asn),"unknownExecute",body),()->unknownAction(id(asn),"unknownExecute",body)));assertEquals(WarehouseJson.write(results.get(0).get()),WarehouseJson.write(results.get(1).get()));}finally{pool.shutdownNow();}assertEquals(1,number("SELECT COUNT(*) FROM wh_asn_custody"));doThrow(WarehouseException.forbidden()).when(scope).assertRecord(eq("asns"),anyMap());assertThrows(WarehouseException.class,()->unknownAction(id(asn),"unknownExecute",body));
    }
    @Test void failedHistoryCaptureRollsBackIdentificationAndAllowsExactRetry() {
        custodyLocation();when(scope.admin()).thenReturn(true);var history=new com.ruoyi.warehouse.finance.StorageHistoryService(records,scope,new com.ruoyi.warehouse.finance.FinanceRuleService(records,scope));tx.execute(s->history.start(map("requestId",UUID.randomUUID().toString(),"confirm",true)));
        var asn=unidentified(create(2),2);var approved=unknown(asn,"unknownApprove",identity(asn,2,"product"));var body=actionBody(approved,map("arrival",arrival(asn),"approval",identityApproval(approved),"quantity",2));
        long batches=number("SELECT COUNT(*) FROM wh_storage_batch"),sequence=number("SELECT last_sequence FROM wh_storage_control");
        jdbc.execute("ALTER TABLE wh_storage_change ADD CONSTRAINT reject_unknown_capture CHECK(entity_kind<>'unidentified' OR sequence_no<="+sequence+")");
        assertThrows(RuntimeException.class,()->unknownAction(id(asn),"unknownExecute",body));
        assertEquals(2,number("SELECT quantity FROM wh_unidentified_occupancy WHERE location_id='custody'"));assertEquals(0,number("SELECT COUNT(*) FROM wh_asn_custody"));assertEquals(0,number("SELECT COUNT(*) FROM wh_inventory"));assertEquals(0,number("SELECT executed_quantity FROM wh_asn_identity_approval"));assertEquals(batches,number("SELECT COUNT(*) FROM wh_storage_batch"));assertEquals(sequence,number("SELECT last_sequence FROM wh_storage_control"));
        jdbc.execute("ALTER TABLE wh_storage_change DROP CONSTRAINT reject_unknown_capture");unknownAction(id(asn),"unknownExecute",body);assertEquals(2,number("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(0,number("SELECT quantity FROM wh_unidentified_occupancy WHERE location_id='custody'"));
    }
    private Map<String,Object> unidentified(Map<String,Object> asn,int q){return unknown(asn,"unknownArrive",map("location","custody","quantity",q,"description","Unknown synthetic goods","barcode","UNREADABLE","evidenceReference","PHOTO-SYNTHETIC"));}
    private Map<String,Object> identity(Map<String,Object> asn,int q,String product){return map("arrival",arrival(asn),"decision","identify","quantity",q,"product",product,"reference","IDENTITY-CONFIRMED");}
    private String arrival(Map<String,Object> asn){return String.valueOf(WarehouseJson.maps(asn.get("unidentifiedArrivals")).get(0).get("id"));}
    private String identityApproval(Map<String,Object> asn){return String.valueOf(WarehouseJson.maps(asn.get("identificationApprovals")).get(0).get("id"));}
    private Map<String,Object> unknown(Map<String,Object> asn,String action,Map<String,Object> body){return unknownAction(id(asn),action,actionBody(asn,body));}
    private Map<String,Object> unknownAction(String id,String action,Map<String,Object> body){return tx.execute(s->new UnidentifiedInboundService(records,scope,new InventoryService(records,scope),service).action(id,action,body));}
    private Map<String,Object> shortClosed(int expected,int accepted){var parent=create(expected);var partial=receive(id(parent),receipt(parent,accepted));var body=closure(partial);body.put("approveShortfall",true);body.put("reason","Original shipment short");return close(id(parent),body);}
    private Map<String,Object> supplementBody(Map<String,Object> parent,int q,String admin,String sku){return map("requestId",UUID.randomUUID().toString(),"_version",parent.get("_version"),"reference","LATE-"+UUID.randomUUID(),"deliveryType","LCL Boxes","lines",List.of(map("product","product","quantity",q)),"adminPolicy",admin,"skuPolicy",sku,"reason","Approved late shipment");}
    private Map<String,Object> supplement(Map<String,Object> parent,Map<String,Object> input){return tx.execute(s->service.lateSupplement(id(parent),input));}
    private void custodyLocation(){record("custodyType","locationTypes",map("name","Quarantine","active",true));record("custody","locations",map("name","Receiving hold","warehouse","warehouse","type","Quarantine","custodyOnly",true,"active",true));}
    private Map<String,Object> stage(Map<String,Object> asn,int quantity){return act(asn,"stage",map("product","product","location","custody","quantity",quantity));}
    private Map<String,Object> act(Map<String,Object> asn,String action,Map<String,Object> values){return custodyAction(id(asn),action,actionBody(asn,values));}
    private Map<String,Object> actionBody(Map<String,Object> asn,Map<String,Object> values){var input=map("requestId",UUID.randomUUID().toString(),"_version",asn.get("_version"),"reason","Synthetic local evidence");input.putAll(values);return input;}
    private Map<String,Object> custodyAction(String id,String action,Map<String,Object> input){return tx.execute(s->new AsnCustodyService(records,scope,new InventoryService(records,scope),service).action(id,action,input));}
    private String task(Map<String,Object> asn){return String.valueOf(WarehouseJson.maps(asn.get("custodyTasks")).get(0).get("id"));}
    private Map<String,Object> create(int quantity){return tx.execute(s->service.save(null,forecast(quantity)));}
    private Map<String,Object> forecast(int q){return map("requestId",UUID.randomUUID().toString(),"client","client","warehouse","warehouse","reference","ASN-"+UUID.randomUUID(),"deliveryType","LCL Boxes","lines",List.of(map("product","product","quantity",q)));}
    private Map<String,Object> receipt(Map<String,Object> asn,int q){return map("requestId",UUID.randomUUID().toString(),"_version",asn.get("_version"),"lines",List.of(line(q,"location","","")));}
    private Map<String,Object> line(int q,String location,String batch,String serial){return map("product","product","quantity",q,"location",location,"batch",batch,"serial",serial);}
    private Map<String,Object> closure(Map<String,Object> asn){return map("requestId",UUID.randomUUID().toString(),"_version",asn.get("_version"),"deliveryType","LCL Boxes","deliveryQuantity",2,"hours","1.250");}
    private Map<String,Object> receive(String id,Map<String,Object> input){return tx.execute(s->service.receive(id,input));}
    private Map<String,Object> close(String id,Map<String,Object> input){return tx.execute(s->service.close(id,input));}
    private void record(String id,String kind,Map<String,Object> data){tx.executeWithoutResult(status -> records.write(kind,id,data,null,id,text(data,"client")));}
    private void update(String id,Map<String,Object> data){jdbc.update("UPDATE wh_record SET business_json=? WHERE id=?",WarehouseJson.write(data),id);}
    private long number(String sql){Long n=jdbc.queryForObject(sql,Long.class);return n==null?0:n;}
    private List<Map<String,Object>> charges(){return records.all("charges");}
    private BigDecimal sumCharges(){return charges().stream().map(c->new BigDecimal(c.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private void assertEmptyReceipt(){for(String table:List.of("wh_inventory","wh_stock_layer","wh_ledger","wh_asn_receipt","wh_asn_receipt_line","wh_finance_source"))assertEquals(0,number("SELECT COUNT(*) FROM "+table),table);assertTrue(charges().isEmpty());}
    private static void money(String expected,Object actual){assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));}
    private static String id(Map<String,Object> row){return text(row,"id");}
    private static String text(Map<String,Object> row,String key){return WarehouseJson.text(row,key);}
    private static Map<String,Object> map(Object... values){var out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
