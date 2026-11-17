package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.stocktake.StocktakeService;
import com.ruoyi.warehouse.support.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ruoyi.warehouse.orders.OrderServiceTest.map;

class StocktakeServiceTest {
    OrderServiceTest f;StocktakeService service;
    @BeforeEach void setup(){f=new OrderServiceTest();f.database();service=new StocktakeService(new WarehouseRepository(f.jdbc,f.scope),f.scope,f.inventory);f.receive("product",5,"location","");}
    @Test void shortageApprovalPreservesSourceEvidenceAndPostsOnce(){
        var count=start();assertEquals(5L,first(count).get("expected"));count=count(count,3,"Two missing");assertEquals(5,stock());count=act(count,"submit");when(f.scope.actor()).thenReturn(8L);
        var body=body(count,"approve","reason","Verified shortage");var approved=raw(count,body);assertEquals("APPROVED",approved.get("status"));assertEquals(3,stock());assertEquals(3,f.scalar("SELECT SUM(remaining_quantity) FROM wh_stock_layer"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_stocktake_lock"));assertEquals(-2,f.scalar("SELECT SUM(quantity_delta) FROM wh_ledger WHERE reference_id='"+id(count)+"'"));
        assertEquals(WarehouseJson.write(approved),WarehouseJson.write(raw(count,body)));assertEquals(3,stock());assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
    }
    @Test void surplusCreatesNewSourceLayerWithCountReference(){var c=count(start(),7,"Two found");c=act(c,"submit");when(f.scope.actor()).thenReturn(8L);act(c,"approve","reason","Verified surplus");assertEquals(7,stock());assertEquals(2,f.scalar("SELECT COUNT(*) FROM wh_stock_layer"));assertEquals(2,f.scalar("SELECT SUM(quantity_delta) FROM wh_ledger WHERE reference_id='"+id(c)+"'"));}
    @Test void frozenLocationsBlockAdjustmentsAndTransfersInBothDirections(){
        f.record("location2","locations",map("name","A-2","warehouse","warehouse","type","Pick"));f.receive("product",2,"location2","");var c=start();
        assertThrows(WarehouseException.class,()->f.receive("product",1,"location",""));
        for(String[] pair:List.of(new String[]{"location","location2"},new String[]{"location2","location"}))assertThrows(WarehouseException.class,()->f.tx.execute(s->f.inventory.move(map("requestId",uuid(),"action","transfer","product","product","warehouse","warehouse","location",pair[0],"toWarehouse","warehouse","toLocation",pair[1],"quantity",1,"reason","Blocked test"))));
        act(c,"cancel","reason","Resume operations");f.receive("product",1,"location","");assertEquals(8,stock());
    }
    @Test void allocationSkipsFrozenLocationButCanUseAnotherLocation(){
        f.record("location2","locations",map("name","A-2","warehouse","warehouse","type","Pick"));f.receive("product",2,"location2","");start();
        var o=f.save(null,f.input(2));var allocated=f.action(id(o),"allocate");assertEquals("location2",WarehouseJson.maps(allocated.get("allocations")).get(0).get("location"));
        var other=f.save(null,f.input(1));assertThrows(WarehouseException.class,()->f.action(id(other),"allocate"));
    }
    @Test void reservedStockCannotBeShippedReleasedOrRemovedDuringCount(){
        String order=id(f.save(null,f.input(4)));f.action(order,"allocate");f.action(order,"pick");var c=start();
        assertThrows(WarehouseException.class,()->f.action(order,"despatch"));assertThrows(WarehouseException.class,()->f.action(order,"cancel"));
        c=count(c,3,"Physical shortage");c=act(c,"submit");when(f.scope.actor()).thenReturn(8L);final var review=c;
        assertThrows(WarehouseException.class,()->act(review,"approve","reason","Cannot consume reservations"));assertEquals(5,stock());assertEquals(4,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_stocktake_lock"));
        act(c,"cancel","reason","Resolve allocated order first");f.action(order,"cancel");assertEquals(0,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));
    }
    @Test void overlappingCountsConflictAndCancellationDoesNotChangeInventory(){var c=start();assertThrows(RuntimeException.class,this::start);var cancelled=act(c,"cancel","reason","Duplicate request");assertEquals("CANCELLED",cancelled.get("status"));assertEquals(5,stock());assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='stocktakes'"));start();}
    @Test void missingCountIsNotZeroAndDifferenceRequiresReason(){var c=start();assertThrows(WarehouseException.class,()->act(c,"submit"));assertThrows(WarehouseException.class,()->count(c,0,""));var zero=count(c,0,"None physically present");zero=act(zero,"submit");when(f.scope.actor()).thenReturn(8L);act(zero,"approve","reason","Verified empty");assertEquals(0,stock());}
    @Test void approvalRequiresAnotherOperatorUnlessAdminExplicitlyOverrides(){var c=act(count(start(),5,""),"submit");assertThrows(WarehouseException.class,()->act(c,"approve","reason","Same actor"));assertThrows(WarehouseException.class,()->act(c,"approve","reason","Not admin","selfApproval",true));when(f.scope.admin()).thenReturn(true);var approved=act(c,"approve","reason","Emergency administrator review","selfApproval",true);assertEquals(true,approved.get("selfApproved"));}
    @Test void priorCounterCannotApproveAfterAnotherCounterOverwritesValues(){var c=count(start(),4,"One missing");when(f.scope.actor()).thenReturn(8L);c=count(c,5,"");c=act(c,"submit");when(f.scope.actor()).thenReturn(7L);final var review=c;assertThrows(WarehouseException.class,()->act(review,"approve","reason","Original counter cannot approve"));when(f.scope.actor()).thenReturn(9L);act(c,"approve","reason","Independent check");}
    @Test void foundProductCreatesPoolOnlyAfterApprovalAndKeepsTracking(){
        f.record("found","products",map("client","client","sku","FOUND","hasBatch",true,"logBatchInbound","Required","hasSerial",true,"logSerialInbound","Required"));var c=count(start(),5,"");
        final var current=c;assertThrows(WarehouseException.class,()->act(current,"found","product","found","location","location","counted",1,"reason","Found"));
        c=act(c,"found","product","found","location","location","batch","LOT-1","serial","SERIAL-1","counted",1,"reason","Found during count");assertEquals(5,stock());c=act(c,"submit");when(f.scope.actor()).thenReturn(8L);act(c,"approve","reason","Verified found serial");assertEquals(6,stock());assertEquals(1,f.scalar("SELECT on_hand FROM wh_inventory WHERE product_id='found' AND serial_number='SERIAL-1'"));
    }
    @Test void serialIdentityCannotBeAddedTwiceAndPartialPostingRollsBack(){
        f.record("serial","products",map("client","client","sku","SERIAL","hasSerial",true));
        f.tx.execute(s->f.inventory.move(map("requestId",uuid(),"action","add","product","serial","warehouse","warehouse","location","location","quantity",1,"serial","S-1","reason","Fixture")));
        f.record("location2","locations",map("name","A-2","warehouse","warehouse","type","Pick"));
        var c=start(List.of("location","location2"));c=countAll(c);c=act(c,"found","product","serial","location","location2","serial","S-1","counted",1,"reason","Incorrect duplicate serial");c=act(c,"submit");when(f.scope.actor()).thenReturn(8L);final var review=c;
        assertThrows(WarehouseException.class,()->act(review,"approve","reason","Reject duplicate"));assertEquals(6,stock());assertEquals(2,f.scalar("SELECT COUNT(*) FROM wh_stocktake_lock"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_stocktake_line WHERE result_json IS NOT NULL"));
    }
    @Test void recountClearsNumbersRetainsEvidenceAndFreeze(){var c=act(count(start(),4,"One missing"),"submit");var reopened=act(c,"reopen","reason","Need second physical count");assertNull(first(reopened).get("counted"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_stocktake_lock"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_audit WHERE action_name='STOCKTAKE_RECOUNT_EVIDENCE'"));}
    @Test void externalFrozenStockChangeRejectsApprovalRatherThanOverwritingIt(){var c=act(count(start(),5,""),"submit");f.jdbc.update("UPDATE wh_inventory SET revision=revision+1");when(f.scope.actor()).thenReturn(8L);assertThrows(WarehouseException.class,()->act(c,"approve","reason","Reject changed snapshot"));assertEquals(5,stock());}
    @Test void auditFailureRollsBackStockAndUnlockAndCanRetryOriginalRequest(){var c=act(count(start(),3,"Two missing"),"submit");when(f.scope.actor()).thenReturn(8L);var body=body(c,"approve","reason","Verified");f.jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_count CHECK(action_name<>'STOCKTAKE_APPROVE')");assertThrows(RuntimeException.class,()->raw(c,body));assertEquals(5,stock());assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_stocktake_lock"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_stocktake_line WHERE result_json IS NOT NULL"));f.jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_count");raw(c,body);assertEquals(3,stock());}
    @Test void sameNonceConcurrentCountRunsOnceAndStaleVersionFails()throws Exception{var c=start();var body=body(c,"count","lines",List.of(map("id",first(c).get("id"),"counted",4,"reason","One missing")));var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return raw(c,body);});var b=pool.submit(()->{start.await();return raw(c,body);});start.countDown();assertEquals(WarehouseJson.write(a.get(10,TimeUnit.SECONDS)),WarehouseJson.write(b.get(10,TimeUnit.SECONDS)));}finally{pool.shutdownNow();}assertThrows(WarehouseException.class,()->count(c,5,""));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_audit WHERE action_name='STOCKTAKE_COUNT_LINE'"));}
    @Test void revokedScopeCannotReadOrReplayApprovedCounts(){var c=start();var body=body(c,"cancel","reason","Test replay");raw(c,body);doThrow(WarehouseException.forbidden()).when(f.scope).assertRecord(eq("stocktakes"),anyMap());assertThrows(WarehouseException.class,()->raw(c,body));assertThrows(WarehouseException.class,()->service.get(id(c)));}
    @Test void shortagesPostBeforeSurplusesSoNetCapacityFits(){
        f.update("location",map("name","A-1","warehouse","warehouse","type","Pick","maxUnits",6));
        f.record("second","products",map("client","client","sku","SECOND"));f.receive("second",1,"location","");var c=start();
        c=act(c,"count","lines",WarehouseJson.maps(c.get("lines")).stream().map(r->map("id",r.get("id"),"counted","product".equals(r.get("product"))?1:5,"reason","Physical identification correction")).toList());
        c=act(c,"submit");when(f.scope.actor()).thenReturn(8L);act(c,"approve","reason","Verified both products");assertEquals(6,stock());assertEquals(5,f.scalar("SELECT on_hand FROM wh_inventory WHERE product_id='second'"));
    }
    @Test void surplusOverCapacityRollsBackAndRetainsFreeze(){
        f.update("location",map("name","A-1","warehouse","warehouse","type","Pick","maxUnits",6));var c=act(count(start(),7,"Two found"),"submit");when(f.scope.actor()).thenReturn(8L);
        assertThrows(WarehouseException.class,()->act(c,"approve","reason","Too many units"));assertEquals(5,stock());assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_stocktake_lock"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_stock_layer"));
    }
    @Test void cancellationStillUnfreezesAfterClientPolicyChanges(){var c=start();f.client.put("infiniteStock",true);f.update("client",f.client);assertEquals("CANCELLED",act(c,"cancel","reason","Policy changed during count").get("status"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_stocktake_lock"));assertEquals(5,stock());}
    @Test void inventoryReadModelSeparatesFrozenStockFromAvailableUnits(){
        when(f.scope.inventoryPredicate(anyList())).thenReturn("1=1");var c=start();var rows=WarehouseJson.maps(f.inventory.list(1,20,"","","","").get("rows"));
        assertEquals(1,rows.size());assertEquals(id(c),rows.get(0).get("stocktakeId"));assertEquals(5L,rows.get(0).get("unreserved"));assertEquals(0L,rows.get(0).get("available"));
        act(c,"cancel","reason","Return stock to availability");var after=WarehouseJson.maps(f.inventory.list(1,20,"","","","").get("rows")).get(0);assertNull(after.get("stocktakeId"));assertEquals(5L,after.get("available"));
    }
    Map<String,Object> start(){return start(List.of("location"));}
    Map<String,Object> start(List<String> locations){return f.tx.execute(s->service.create(map("requestId",uuid(),"client","client","warehouse","warehouse","reference","COUNT-"+uuid(),"locations",locations)));}
    Map<String,Object> count(Map<String,Object> c,long n,String reason){return act(c,"count","lines",List.of(map("id",first(c).get("id"),"counted",n,"reason",reason)));}
    Map<String,Object> countAll(Map<String,Object> c){return act(c,"count","lines",WarehouseJson.maps(c.get("lines")).stream().map(r->map("id",r.get("id"),"counted",r.get("expected"),"reason","")).toList());}
    Map<String,Object> act(Map<String,Object> c,String action,Object... fields){return raw(c,body(c,action,fields));}
    Map<String,Object> raw(Map<String,Object> c,Map<String,Object> body){return f.tx.execute(s->service.action(id(c),body));}
    static Map<String,Object> body(Map<String,Object> c,String action,Object... fields){var body=map("requestId",uuid(),"_version",c.get("_version"),"action",action);body.putAll(map(fields));return body;}
    static Map<String,Object> first(Map<String,Object> c){return WarehouseJson.maps(c.get("lines")).get(0);}
    static String id(Map<String,Object> c){return c.get("id").toString();}
    static String uuid(){return UUID.randomUUID().toString();}
    long stock(){return f.scalar("SELECT SUM(on_hand) FROM wh_inventory");}
}
