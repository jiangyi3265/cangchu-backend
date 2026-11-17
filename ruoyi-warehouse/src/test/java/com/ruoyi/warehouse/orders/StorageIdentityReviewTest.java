package com.ruoyi.warehouse.orders;
import com.ruoyi.warehouse.finance.*;
import com.ruoyi.warehouse.support.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.warehouse.orders.OrderServiceTest.map;
import static com.ruoyi.warehouse.orders.StorageHistoryTest.money;

class StorageIdentityReviewTest {
    StorageHistoryTest h;StorageIdentityReviewService service;
    @BeforeEach void setup(){h=new StorageHistoryTest();h.setup();h.unknownSetup();h.unknown("unknown",3);h.start();service=new StorageIdentityReviewService(h.records,h.f.scope,new FinanceRuleService(h.records,h.f.scope));actor(7);}
    void actor(long id){when(h.f.scope.actor()).thenReturn(id);}
    Map<String,Object> input(){return map("client","client","from",h.day.toString(),"to",h.day.toString(),"planInput",map("allocations",List.of(map("arrival","unknown","product","product","quantity",3)),"productFacts",List.of(),"reason","Verified historical product identity","evidenceReference","SIGNED-SYNTHETIC"));}
    Map<String,Object> plan(Map<String,Object> in){return h.f.tx.execute(s->service.preview(in));}
    Map<String,Object> save(Map<String,Object> in){var body=new LinkedHashMap<>(in);body.put("quoteHash",plan(in).get("quoteHash"));body.put("requestId",UUID.randomUUID().toString());return h.f.tx.execute(s->service.save(body));}
    Map<String,Object> action(Map<String,Object> row,String action){return h.f.tx.execute(s->service.action(String.valueOf(row.get("id")),map("requestId",UUID.randomUUID().toString(),"_version",row.get("_version"),"action",action,"reason","Synthetic reviewed decision")));}
    Map<String,Object> approve(){var row=save(input());actor(8);row=action(row,"approve");actor(7);return row;}
    @SuppressWarnings("unchecked") Map<String,Object> object(Map<String,Object> row,String key){return (Map<String,Object>)row.get(key);}
    @Test void fullReviewUsesHistoricalPricesAndDoesNotRewritePhysicalHistory(){
        String original=WarehouseJson.write(h.history.day("client",h.day.toString()));long sequence=h.sequence();assertThrows(WarehouseException.class,()->h.preview(h.day,h.day));var draft=save(input());assertThrows(WarehouseException.class,()->action(draft,"approve"));
        h.update("clients","client",map("unitStorageFee",99));actor(8);var approved=action(draft,"approve");actor(7);var quote=h.preview(h.day,h.day);money("26",quote.get("amount"));assertEquals(original,WarehouseJson.write(h.history.day("client",h.day.toString())));assertEquals(sequence+1,h.sequence());
        var result=h.commit(quote);assertEquals("CONSUMED",service.get(String.valueOf(approved.get("id"))).get("status"));assertEquals(10,h.f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(3,h.f.scalar("SELECT SUM(quantity) FROM wh_unidentified_occupancy"));assertEquals(3,h.f.scalar("SELECT COUNT(*) FROM wh_storage_identity_event"));assertEquals(1,WarehouseJson.maps(result.get("charges")).size());actor(8);assertThrows(WarehouseException.class,()->action(approved,"revoke"));
    }
    @Test void approverCannotPostTheirOwnApprovalAndFailureLeavesItUsable(){var approved=approve();actor(8);var quote=h.preview(h.day,h.day);assertThrows(WarehouseException.class,()->h.commit(quote));assertEquals(0,h.f.scalar("SELECT COUNT(*) FROM wh_storage_billed_day"));assertEquals("APPROVED",service.get(String.valueOf(approved.get("id"))).get("status"));actor(7);h.commit(quote);}
    @Test void allocationsMustCoverExactHeldQuantitiesAndCannotOverrideHistoricalFacts(){
        for(int q:List.of(2,4)){var in=input();object(in,"planInput").put("allocations",List.of(map("arrival","unknown","product","product","quantity",q)));assertThrows(WarehouseException.class,()->plan(in));}
        var override=input();object(override,"planInput").put("productFacts",List.of(map("product","product","unitStoragePrice",1)));assertThrows(WarehouseException.class,()->plan(override));
        var duplicate=input();object(duplicate,"planInput").put("allocations",List.of(map("arrival","unknown","product","product","quantity",1),map("arrival","unknown","product","product","quantity",2)));assertThrows(WarehouseException.class,()->plan(duplicate));
        h.f.record("foreign","products",map("client","other","sku","FOREIGN"));var foreign=input();object(foreign,"planInput").put("allocations",List.of(map("arrival","unknown","product","foreign","quantity",3)));assertThrows(WarehouseException.class,()->plan(foreign));
    }
    @Test void productsCreatedAfterThePeriodNeedExplicitEvidencedFacts(){
        h.f.record("later","products",map("client","client","sku","LATER","unitStoragePrice",999));var in=input();object(in,"planInput").put("allocations",List.of(map("arrival","unknown","product","later","quantity",3)));assertThrows(WarehouseException.class,()->plan(in));
        object(in,"planInput").put("productFacts",List.of(map("product","later","unitStoragePrice","1.25")));money("23.75",object(plan(in),"quote").get("amount"));var draft=save(in);actor(8);action(draft,"approve");actor(7);money("23.75",h.preview(h.day,h.day).get("amount"));
    }
    @Test void sharedSkuIsAggregatedBeforeCartonRounding(){
        h=new StorageHistoryTest();h.setup();h.f.client.put("storageType","VolumetricCarton");h.f.update("client",h.f.client);h.f.update("product",map("client","client","sku","SYNTHETIC","unitsPerCarton",6,"cartonHeight",100,"cartonLength",100,"cartonDepth",100));h.unknownSetup();h.unknown("unknown",2);h.start();service=new StorageIdentityReviewService(h.records,h.f.scope,new FinanceRuleService(h.records,h.f.scope));actor(7);
        var in=input();object(in,"planInput").put("allocations",List.of(map("arrival","unknown","product","product","quantity",2)));money("4",object(plan(in),"quote").get("amount"));var draft=save(in);actor(8);action(draft,"approve");actor(7);money("4",h.preview(h.day,h.day).get("amount"));
    }
    @Test void productRevisionChangeInvalidatesApproval(){var draft=save(input());h.update("products","product",map("name","Changed identity"));actor(8);assertThrows(WarehouseException.class,()->action(draft,"approve"));}
    @Test void reviewRangeCannotSilentlySkipAnAlreadyBilledPeriod(){
        approve();h.commit(h.preview(h.day,h.day));var in=input();in.put("to",h.day.plusDays(1).toString());
        assertThrows(WarehouseException.class,()->plan(in));
        assertThrows(WarehouseException.class,()->h.f.tx.execute(s->service.context("client",h.day.toString(),h.day.plusDays(1).toString())));
        in.put("from",h.day.plusDays(1).toString());money("26",object(plan(in),"quote").get("amount"));
    }
    @Test void productRevisionChangeInvalidatesPosting(){approve();var quote=h.preview(h.day,h.day);h.update("products","product",map("name","Changed after approval"));assertThrows(WarehouseException.class,()->h.commit(quote));assertEquals(0,h.f.scalar("SELECT COUNT(*) FROM wh_storage_billed_day"));}
    @Test void revocationInvalidatesAnAlreadyDisplayedBillingQuote(){var approved=approve();var quote=h.preview(h.day,h.day);actor(8);action(approved,"revoke");actor(7);assertThrows(WarehouseException.class,()->h.commit(quote));assertEquals(0,h.f.scalar("SELECT COUNT(*) FROM wh_storage_billed_day"));}
    @Test void auditFailureRollsBackReviewConsumptionChargeAndDayClaims(){var approved=approve();var quote=h.preview(h.day,h.day);h.f.jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_storage_review CHECK(action_name<>'STORAGE_IDENTITY_CONSUME')");assertThrows(RuntimeException.class,()->h.commit(quote));assertEquals(0,h.f.scalar("SELECT COUNT(*) FROM wh_storage_billed_day"));assertEquals(0,h.f.scalar("SELECT COUNT(*) FROM wh_finance_source"));assertEquals("APPROVED",service.get(String.valueOf(approved.get("id"))).get("status"));h.f.jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_storage_review");h.commit(quote);}
    @Test void checksumAndCurrentWarehouseScopeProtectTheReview(){var approved=approve();when(h.f.scope.granted("WAREHOUSE","warehouse")).thenReturn(false);assertThrows(WarehouseException.class,()->service.get(String.valueOf(approved.get("id"))));assertThrows(WarehouseException.class,()->h.preview(h.day,h.day));when(h.f.scope.granted("WAREHOUSE","warehouse")).thenReturn(true);h.f.jdbc.update("UPDATE wh_storage_identity_review SET document_hash=REPEAT('0',64)");assertThrows(WarehouseException.class,()->service.get(String.valueOf(approved.get("id"))));}
    @Test void concurrentIdenticalPostingConsumesReviewOnce() throws Exception {
        var approved=approve();var quote=h.preview(h.day,h.day);var input=h.input(h.day,h.day);input.put("requestId",UUID.randomUUID().toString());input.put("quoteHash",quote.get("quoteHash"));var pool=Executors.newFixedThreadPool(2);try{var tasks=List.<Callable<Map<String,Object>>>of(()->h.f.tx.execute(s->h.finance.commit(input)),()->h.f.tx.execute(s->h.finance.commit(input)));var result=pool.invokeAll(tasks);assertEquals(WarehouseJson.write(result.get(0).get()),WarehouseJson.write(result.get(1).get()));}finally{pool.shutdownNow();}assertEquals(1,h.f.scalar("SELECT COUNT(*) FROM wh_storage_identity_event WHERE action_name='CONSUME'"));assertEquals("CONSUMED",service.get(String.valueOf(approved.get("id"))).get("status"));
    }
}
