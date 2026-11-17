package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.warehouse.orders.OrderServiceTest.map;

class OrderWorkTest {
    OrderServiceTest f;
    @BeforeEach void setup(){f=new OrderServiceTest();f.database();f.client.put("requireScan",true);f.update("client",f.client);}
    @Test void mandatoryScanPickingMultiPackageAndDespatchPreserveStockAndChargeOnce(){
        var order=allocated(3);String id=id(order);assertTrue((Boolean)order.get("scanRequired"));
        assertThrows(WarehouseException.class,()->f.action(id,"pick"));
        for(int i=0;i<3;i++)order=scan(order,"SYNTHETIC","");
        assertTrue((Boolean)work(order).get("scanningComplete"));assertEquals(3,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(3,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));
        order=f.action(id,"pick");assertThrows(WarehouseException.class,()->f.action(id,"despatch"));
        order=pack(order,1,"BOX-A");assertFalse((Boolean)work(order).get("packingComplete"));assertThrows(WarehouseException.class,()->f.action(id,"despatch"));
        order=pack(order,2,"BOX-B");assertTrue((Boolean)work(order).get("packingComplete"));assertEquals("2.500",work(order).get("totalWeightKg"));
        var body=f.actionInput("despatch");var shipped=f.act(id,body);assertEquals("DESPATCHED",shipped.get("status"));assertEquals("2.500",shipped.get("confirmedPackingWeight"));assertEquals(2,((Number)shipped.get("packageCount")).intValue());
        assertEquals(0,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(0,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_order_billing"));assertEquals(2,f.scalar("SELECT COUNT(*) FROM wh_order_package WHERE state='DESPATCHED'"));assertEquals(WarehouseJson.write(shipped),WarehouseJson.write(f.act(id,body)));
    }
    @Test void barcodeAndUpcMustMatchExactProductAndSkuFallbackOnlyWithoutCodes(){
        f.update("product",map("client","client","sku","SYNTHETIC","barcode","000123","upc","987654"));var order=allocated(2);
        final var first=order;assertThrows(WarehouseException.class,()->scan(first,"123",""));assertThrows(WarehouseException.class,()->scan(first,"SYNTHETIC",""));
        order=scan(order,"000123","");order=scan(order,"987654","");assertTrue((Boolean)work(order).get("scanningComplete"));
    }
    @Test void ambiguousCodesAreRejectedEvenWithExplicitAllocationSelection(){
        f.update("product",map("client","client","sku","SYNTHETIC","barcode","DUP"));f.record("product2","products",map("client","client","sku","OTHER","upc","DUP"));var order=allocated(1);
        assertThrows(WarehouseException.class,()->scan(order,"DUP",""));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_scan"));
    }
    @Test void serialMustMatchReservedUnitAndCannotBeScannedTwice(){
        f.update("product",map("client","client","sku","SYNTHETIC","hasSerial",true,"logSerialInbound","Required"));
        f.tx.execute(s->f.inventory.move(map("requestId",uuid(),"action","add","product","product","warehouse","warehouse","location","location","quantity",1,"reason","Serial test","serial","SERIAL-A")));
        var order=f.action(id(f.save(null,f.input(1))),"allocate");assertThrows(WarehouseException.class,()->scan(order,"SYNTHETIC","SERIAL-B"));assertThrows(WarehouseException.class,()->scan(order,"SYNTHETIC",""));
        var done=scan(order,"SYNTHETIC","SERIAL-A");assertThrows(WarehouseException.class,()->scan(done,"SYNTHETIC","SERIAL-A"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_order_scan"));
    }
    @Test void aScanIsOneUnitAndCannotInjectQuantityOrProtectedFields(){var order=allocated(2);var body=scanBody(order,"SYNTHETIC","");body.put("quantity",2);assertThrows(WarehouseException.class,()->act(order,body));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_scan"));}
    @Test void duplicateNonceReturnsSameScanAndStaleVersionCannotAdvance(){
        var order=allocated(2);var body=scanBody(order,"SYNTHETIC","");var result=act(order,body);assertEquals(WarehouseJson.write(result),WarehouseJson.write(act(order,body)));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_order_scan"));
        body.put("barcode","CHANGED");assertThrows(WarehouseException.class,()->act(order,body));assertThrows(WarehouseException.class,()->scan(order,"SYNTHETIC",""));
    }
    @Test void scannerPolicyCannotBeDisabledAfterAllocationOrAfterVoluntaryScanning(){
        var first=allocated(1);f.client.put("requireScan",false);f.update("client",f.client);assertThrows(WarehouseException.class,()->f.action(id(first),"pick"));
        var optional=allocated(1);assertFalse((Boolean)optional.get("scanRequired"));var scanned=scan(optional,"SYNTHETIC","");assertTrue((Boolean)scanned.get("scanRequired"));f.action(id(scanned),"pick");assertThrows(WarehouseException.class,()->f.action(id(scanned),"despatch"));
    }
    @Test void enablingPolicyAfterManualPickingRequiresReopeningAndScanning(){
        f.client.put("requireScan",false);f.update("client",f.client);var order=allocated(1);order=f.action(id(order),"pick");f.client.put("requireScan",true);f.update("client",f.client);String id=id(order);assertThrows(WarehouseException.class,()->f.action(id,"despatch"));
        order=act(order,body(order,"reopenpick","reason","Scan policy enabled"));assertEquals("ALLOCATED",order.get("status"));order=scan(order,"SYNTHETIC","");order=f.action(id,"pick");order=pack(order,1,"NEW-POLICY");assertEquals("DESPATCHED",f.action(id,"despatch").get("status"));
    }
    @Test void unpackThenUndoScanPreservesHistoryAndRequiresPickingAgain(){
        var order=scan(allocated(1),"SYNTHETIC","");order=f.action(id(order),"pick");order=pack(order,1,"BOX");var scan=rows(work(order),"scans").get(0);var packedOrder=order;
        assertThrows(WarehouseException.class,()->act(packedOrder,body(packedOrder,"unscan","scanId",scan.get("id"),"reason","Correction")));
        order=act(order,body(order,"unpack","packageId",rows(work(order),"packages").get(0).get("id"),"reason","Wrong box"));
        order=act(order,body(order,"unscan","scanId",scan.get("id"),"reason","Wrong scan"));assertEquals("ALLOCATED",order.get("status"));assertEquals("VOID",rows(work(order),"scans").get(0).get("state"));assertEquals("VOID",rows(work(order),"packages").get(0).get("state"));assertEquals(1,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));
        order=scan(order,"SYNTHETIC","");order=f.action(id(order),"pick");order=pack(order,1,"REPACK");assertTrue((Boolean)work(order).get("packingComplete"));
    }
    @Test void cancellationVoidsEvidenceAndReleasesOnlyReservationsWithoutCharges(){
        var order=scan(allocated(1),"SYNTHETIC","");order=f.action(id(order),"pick");order=pack(order,1,"BOX");var cancelled=f.action(id(order),"cancel");assertEquals("CANCELLED",cancelled.get("status"));assertEquals(1,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(0,f.scalar("SELECT SUM(reserved) FROM wh_inventory"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_scan WHERE state='ACTIVE'"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_package WHERE state='SEALED'"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));
    }
    @Test void packageCannotMixAnotherOrderAllocationOrExceedRemainingGoods(){
        var order=scan(allocated(1),"SYNTHETIC","");order=f.action(id(order),"pick");var other=allocated(1);var invalid=packBody(order,1,"WRONG");invalid.put("lines",List.of(map("allocationId",allocation(other),"quantity",1)));final var picked=order;assertThrows(WarehouseException.class,()->act(picked,invalid));assertThrows(WarehouseException.class,()->pack(picked,2,"EXCESS"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_package"));
    }
    @Test void packageMeasurementsMustBePositiveBoundedAndExactDecimals(){
        var order=scan(allocated(1),"SYNTHETIC","");order=f.action(id(order),"pick");var picked=order;
        for(Object value:List.of("0","-1","1.0001","100001","1e1000000000","NaN")){var input=packBody(order,1,"INVALID");input.put("weightKg",value);assertThrows(WarehouseException.class,()->act(picked,input));}
        var input=packBody(order,1,"INVALID");input.put("lengthCm","10.001");assertThrows(WarehouseException.class,()->act(picked,input));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_package"));
    }
    @Test void auditFailureRollsBackScanNonceAndOrderRevision(){
        var order=allocated(1);var body=scanBody(order,"SYNTHETIC","");f.jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_scan CHECK(action_name<>'ORDER_WORK_SCAN')");assertThrows(RuntimeException.class,()->act(order,body));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_scan"));assertEquals(order.get("_version"),f.service.get(id(order)).get("_version"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='"+body.get("requestId")+"'"));
        f.jdbc.execute("ALTER TABLE wh_audit DROP CONSTRAINT reject_scan");assertTrue((Boolean)work(act(order,body)).get("scanningComplete"));
    }
    @Test void despatchFailureRollsBackPackageStateStockAndFees(){
        var order=scan(allocated(1),"SYNTHETIC","");order=f.action(id(order),"pick");order=pack(order,1,"BOX");String id=id(order);f.jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_scan_ship CHECK(action_name<>'MANUAL_ORDER_DESPATCH')");assertThrows(RuntimeException.class,()->f.action(id,"despatch"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_order_package WHERE state='SEALED'"));assertEquals(1,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_order_billing"));
    }
    @Test void concurrentRetryRecordsOneScanAndCompetingVersionsCannotOverScan() throws Exception {
        var order=allocated(2);var body=scanBody(order,"SYNTHETIC","");var pool=Executors.newFixedThreadPool(2);try{var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return act(order,body);});var b=pool.submit(()->{start.await();return act(order,body);});start.countDown();assertEquals(WarehouseJson.write(a.get(10,TimeUnit.SECONDS)),WarehouseJson.write(b.get(10,TimeUnit.SECONDS)));}finally{pool.shutdownNow();}assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_order_scan"));
    }
    @Test void revokedScopeRejectsPreviouslyCompletedScanReplay(){var order=allocated(1);var body=scanBody(order,"SYNTHETIC","");act(order,body);doThrow(WarehouseException.forbidden()).when(f.scope).assertRecord(eq("orders"),anyMap());assertThrows(WarehouseException.class,()->act(order,body));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_order_scan"));}
    @Test void closedOrdersCannotScanOrUndoPackages(){var order=scan(allocated(1),"SYNTHETIC","");order=f.action(id(order),"pick");order=pack(order,1,"BOX");var shipped=f.action(id(order),"despatch");assertThrows(WarehouseException.class,()->scan(shipped,"SYNTHETIC",""));assertThrows(WarehouseException.class,()->act(shipped,body(shipped,"unpack","packageId",rows(work(shipped),"packages").get(0).get("id"),"reason","Invalid")));}
    @Test void packageNonceReplayIsStableAndDuplicatePackageCodesCannotPostTwice(){var order=scan(allocated(2),"SYNTHETIC","");order=scan(order,"SYNTHETIC","");order=f.action(id(order),"pick");var input=packBody(order,1,"BOX");var packed=act(order,input);assertEquals(WarehouseJson.write(packed),WarehouseJson.write(act(order,input)));assertThrows(RuntimeException.class,()->pack(packed,1,"BOX"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_order_package"));}
    Map<String,Object> allocated(int q){f.receive("product",q,"location","");return f.action(id(f.save(null,f.input(q))),"allocate");}
    Map<String,Object> scan(Map<String,Object> order,String barcode,String serial){return act(order,scanBody(order,barcode,serial));}
    Map<String,Object> scanBody(Map<String,Object> order,String barcode,String serial){return body(order,"scan","allocationId",allocation(order),"barcode",barcode,"serial",serial);}
    Map<String,Object> pack(Map<String,Object> order,int q,String code){return act(order,packBody(order,q,code));}
    Map<String,Object> packBody(Map<String,Object> order,int q,String code){return body(order,"pack","packageCode",code,"weightKg","1.250","lengthCm","30.00","widthCm","20.00","heightCm","10.00","lines",List.of(map("allocationId",allocation(order),"quantity",q)));}
    Map<String,Object> act(Map<String,Object> order,Map<String,Object> input){return f.tx.execute(s->f.service.workAction(id(order),input));}
    static Map<String,Object> body(Map<String,Object> order,String action,Object... fields){var value=map("requestId",uuid(),"_version",order.get("_version"),"action",action);value.putAll(map(fields));return value;}
    static String allocation(Map<String,Object> order){return String.valueOf(rows(order,"allocations").get(0).get("id"));}
    static String id(Map<String,Object> order){return String.valueOf(order.get("id"));}
    static String uuid(){return UUID.randomUUID().toString();}
    static Map<String,Object> work(Map<String,Object> order){return WarehouseJson.map(WarehouseJson.write(order.get("work")));}
    static List<Map<String,Object>> rows(Map<String,Object> value,String key){return WarehouseJson.maps(value.get(key));}
}
