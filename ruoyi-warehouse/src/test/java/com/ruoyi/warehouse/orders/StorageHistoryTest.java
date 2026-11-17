package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.finance.*;
import com.ruoyi.warehouse.support.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.warehouse.orders.OrderServiceTest.map;

/** Historical timestamps below are isolated synthetic fixtures, never a public backdating API. */
class StorageHistoryTest {
    OrderServiceTest f;WarehouseRepository records;StorageHistoryService history;FinanceWorkflowService finance;LocalDate day;
    @BeforeEach void setup(){
        f=new OrderServiceTest();f.database();when(f.scope.admin()).thenReturn(true);records=new WarehouseRepository(f.jdbc,f.scope);var rules=new FinanceRuleService(records,f.scope);history=new StorageHistoryService(records,f.scope,rules);finance=new FinanceWorkflowService(records,f.scope,rules);
        f.jdbc.execute("CREATE TABLE wh_finance_source(source_key VARCHAR(191) PRIMARY KEY,charge_id VARCHAR(64) UNIQUE REFERENCES wh_record(id),evidence_json VARCHAR(4000000))");
        day=LocalDate.now(ZoneOffset.UTC).minusDays(14).with(java.time.DayOfWeek.MONDAY);
        f.client.putAll(map("storageType","StorageUnits","storageFrequency","Daily","unitStorageFee",2,"storageRate",2,"stockManagementFee",5));f.update("client",f.client);
        f.update("product",map("client","client","sku","SYNTHETIC","active",true,"volume","0.1","unitsPerCarton",4,"cartonHeight",100,"cartonLength",100,"cartonDepth",100,"unitsPerPallet",6,"unitStoragePrice",0));
        f.record("location2","locations",map("name","A-2","warehouse","warehouse","type","Pick","active",true));
        f.record("price","locationPrices",map("client","client","locationType","Pick","frequency","Daily","cost",3,"active",true));
        f.receive("product",10,"location","");f.jdbc.update("UPDATE wh_stock_layer SET received_at=?",Timestamp.from(day.minusYears(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
    }
    void start(){f.tx.execute(s->history.start(map("requestId",UUID.randomUUID().toString(),"confirm",true)));at(1,day.minusDays(1),23);f.jdbc.update("UPDATE wh_storage_control SET started_at=?",Timestamp.from(day.minusDays(1).atTime(23,0).toInstant(ZoneOffset.UTC)));}
    void at(long seq,LocalDate d,int hour){f.jdbc.update("UPDATE wh_storage_batch SET effective_at=? WHERE sequence_no=?",Timestamp.from(d.atTime(hour,0).toInstant(ZoneOffset.UTC)),seq);}
    long sequence(){return f.scalar("SELECT last_sequence FROM wh_storage_control");}
    Map<String,Object> input(LocalDate from,LocalDate to){return map("client","client","mode","Storage","from",from.toString(),"to",to.toString());}
    Map<String,Object> preview(LocalDate from,LocalDate to){return f.tx.execute(s->finance.preview(input(from,to)));}
    Map<String,Object> commit(Map<String,Object> quote){var in=input(LocalDate.parse(String.valueOf(quote.get("from"))),LocalDate.parse(String.valueOf(quote.get("to"))));in.put("quoteHash",quote.get("quoteHash"));in.put("requestId",UUID.randomUUID().toString());return f.tx.execute(s->finance.commit(in));}
    void update(String kind,String id,Map<String,Object> fields){f.tx.execute(s->{var old=records.get(kind,id,true);var data=WarehouseJson.copy(old);for(String k:List.of("id","_version","createdAt","updatedAt"))data.remove(k);data.putAll(fields);return records.write(kind,id,data,((Number)old.get("_version")).longValue(),id,WarehouseJson.text(data,"client"));});}
    void move(String action,String source,long quantity,String target){f.tx.execute(s->f.inventory.move(map("requestId",UUID.randomUUID().toString(),"action",action,"product","product","warehouse","warehouse","location",source,"quantity",quantity,"reason","Synthetic storage history", "toWarehouse",target.isEmpty()?"":"warehouse","toLocation",target)));}
    static void money(String expected,Object value){assertEquals(0,new java.math.BigDecimal(expected).compareTo(new java.math.BigDecimal(value.toString())));}

    @Test void historyStartsOnceAndRefusesIncompleteDaysAndBackdatedRequests(){assertThrows(WarehouseException.class,()->preview(day,day));start();assertTrue((Boolean)history.status().get("enabled"));assertEquals(day.toString(),history.status().get("firstCompleteDay"));f.tx.execute(s->history.start(map("requestId",UUID.randomUUID().toString(),"confirm",true)));assertEquals(1,sequence());assertThrows(WarehouseException.class,()->preview(day.minusDays(1),day));assertThrows(WarehouseException.class,()->f.tx.execute(s->history.start(map("requestId",UUID.randomUUID().toString(),"confirm",true,"date",day.toString()))));}
    @Test void priceAndQuantityAreHistoricalAndFutureEditsDoNotChangeOldQuote(){start();move("remove","location",2,"");at(sequence(),day,12);update("clients","client",map("unitStorageFee",3));at(sequence(),day,18);var original=preview(day,day);money("24",original.get("amount"));update("clients","client",map("unitStorageFee",99));assertEquals(original.get("quoteHash"),preview(day,day).get("quoteHash"));var captured=history.day("client",day.toString());assertEquals(8L,((Number)WarehouseJson.maps(captured.get("stock")).get(0).get("onhand")).longValue());money("3",((Map<?,?>)captured.get("client")).get("unitStorageFee"));}
    @Test void transferCapturesOneCommittedStateAndPeakSurvivesLaterVacatingLocation(){f.client.put("storageType","HighestPointLocationTypeUsage");f.update("client",f.client);start();move("transfer","location",3,"location2");at(sequence(),day,12);long transferSequence=sequence();assertEquals(2,f.scalar("SELECT COUNT(*) FROM wh_storage_change WHERE sequence_no="+transferSequence+" AND entity_kind='inventory'"));move("transfer","location2",3,"location");at(sequence(),day,18);money("6",preview(day,day).get("amount"));var proof=history.day("client",day.toString());assertEquals(1,WarehouseJson.maps(proof.get("stock")).size());assertEquals(2L,((Map<?,?>)proof.get("locationPeaks")).get("Pick"));}
    @Test void allSevenStorageModesUseHistoricalInputs(){
        Map<String,String> cases=new LinkedHashMap<>();cases.put("StorageUnits","20");cases.put("Volumetric","2");cases.put("VolumetricCarton","6");cases.put("PerPalletUnit","4");cases.put("LocationTypeUsage","3");cases.put("HighestPointLocationTypeUsage","3");cases.put("StockManagementFee","5");
        start();int n=0;for(var c:cases.entrySet()){LocalDate d=day.plusDays(n++);update("clients","client",map("storageType",c.getKey()));at(sequence(),d,10);money(c.getValue(),preview(d,d).get("amount"));}
    }
    @Test void weeklyBillingUsesClosingSnapshotAndRequiresFullPeriod(){f.client.put("storageFrequency","Weekly");f.update("client",f.client);start();move("remove","location",4,"");at(sequence(),day.plusDays(5),10);money("12",preview(day,day.plusDays(6)).get("amount"));assertThrows(WarehouseException.class,()->preview(day.plusDays(1),day.plusDays(6)));assertThrows(WarehouseException.class,()->preview(day,day.plusDays(5)));}
    @Test void modeChangeInsideWeekIsNotSilentlyRepriced(){f.client.put("storageFrequency","Weekly");f.update("client",f.client);start();update("clients","client",map("storageType","StockManagementFee"));at(sequence(),day.plusDays(2),12);assertThrows(WarehouseException.class,()->preview(day,day.plusDays(6)));}
    @Test void committedChargesClaimEveryDayAndCannotOverlap(){start();var quoted=preview(day,day.plusDays(2));money("60",quoted.get("amount"));var posted=commit(quoted);assertEquals(3,WarehouseJson.maps(posted.get("charges")).size());assertEquals(3,f.scalar("SELECT COUNT(*) FROM wh_storage_billed_day"));assertTrue(WarehouseJson.maps(preview(day,day.plusDays(2)).get("entries")).isEmpty());assertThrows(WarehouseException.class,()->commit(quoted));}
    @Test void postingFailureRollsBackFeesDaysAndRequestJournal(){start();var quote=preview(day,day.plusDays(1));f.jdbc.execute("ALTER TABLE wh_storage_billed_day ADD CONSTRAINT reject_day CHECK(billing_day<>'"+day.plusDays(1)+"')");assertThrows(RuntimeException.class,()->commit(quote));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_storage_billed_day"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_finance_source"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));}
    @Test void historyFailureRollsBackTheOriginalPhysicalOperation(){start();f.jdbc.execute("ALTER TABLE wh_storage_batch ADD CONSTRAINT reject_capture CHECK(sequence_no<2)");assertThrows(RuntimeException.class,()->move("remove","location",2,""));assertEquals(10,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));assertEquals(1,sequence());f.jdbc.execute("ALTER TABLE wh_storage_batch DROP CONSTRAINT reject_capture");move("remove","location",2,"");assertEquals(2,sequence());assertEquals(8,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));}
    @Test void rolledBackBusinessOperationDoesNotLeaveHistory(){start();f.jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT reject_movement CHECK(action_name<>'MANUAL_STOCK_REMOVE')");assertThrows(RuntimeException.class,()->move("remove","location",1,""));assertEquals(1,sequence());}
    @Test void allocationDoesNotChangePhysicalHistoryButDespatchDoes(){start();String id=String.valueOf(f.save(null,f.input(2)).get("id"));f.action(id,"allocate");f.action(id,"pick");assertEquals(1,sequence());f.action(id,"despatch");assertEquals(2,sequence());at(2,day,12);money("16",preview(day,day).get("amount"));}
    @Test void checksumFailureAndMissingWarehouseScopeBlockHistoricalReads(){start();when(f.scope.granted("WAREHOUSE","warehouse")).thenReturn(false);assertThrows(WarehouseException.class,()->history.day("client",day.toString()));when(f.scope.granted("WAREHOUSE","warehouse")).thenReturn(true);f.jdbc.update("UPDATE wh_storage_change SET payload_hash=REPEAT('0',64) WHERE entity_kind='inventory'");assertThrows(WarehouseException.class,()->preview(day,day));}
    @Test void partialDateClaimsNeverGetChargedAgainUnderAnotherPeriod(){f.client.put("storageFrequency","Weekly");f.update("client",f.client);start();f.record("claimed","charges",map("client","client","amount",1));f.jdbc.update("INSERT INTO wh_storage_billed_day VALUES('client',?,'claimed')",day.toString());assertThrows(WarehouseException.class,()->preview(day,day.plusDays(6)));}
    @Test void simultaneousInventoryTransactionsKeepBothCommittedHistoryBatches() throws Exception {start();var pool=Executors.newFixedThreadPool(2);try{var tasks=List.<Callable<Void>>of(()->{move("remove","location",1,"");return null;},()->{move("remove","location",2,"");return null;});for(var r:pool.invokeAll(tasks))r.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(3,sequence());assertEquals(7,f.scalar("SELECT SUM(on_hand) FROM wh_inventory"));}
    @Test void changingRuleOwnershipRemovesOldRateWithoutLeakingNewClientData(){
        f.client.put("storageType","LocationTypeUsage");f.update("client",f.client);start();
        update("locationPrices","price",map("client","other-client","cost",999));at(sequence(),day,12);
        assertTrue(WarehouseJson.maps(history.day("client",day.toString()).get("locationPrices")).isEmpty());
        assertTrue(WarehouseJson.maps(history.day("client",day.plusDays(1).toString()).get("locationPrices")).isEmpty());
        assertThrows(WarehouseException.class,()->preview(day,day));
    }
    @Test void monthlyPeriodAndGlobalRateFallbackUseHistoricalPrices(){
        day=LocalDate.now(ZoneOffset.UTC).minusMonths(1).withDayOfMonth(1);f.client.putAll(map("storageType","LocationTypeUsage","storageFrequency","Monthly"));f.update("client",f.client);
        f.record("global-price","locationPrices",map("client","","locationType","Pick","frequency","Monthly","cost",7,"active",true));start();
        money("7",preview(day,day.withDayOfMonth(day.lengthOfMonth())).get("amount"));
        update("locationPrices","global-price",map("cost",99));
        money("7",preview(day,day.withDayOfMonth(day.lengthOfMonth())).get("amount"));
    }

    @Test void historicalPeriodsWithDifferentCurrenciesCannotBeSummed(){start();update("clients","client",map("currency","EUR"));at(sequence(),day.plusDays(1),12);assertThrows(WarehouseException.class,()->preview(day,day.plusDays(1)));money("20",preview(day,day).get("amount"));money("20",preview(day.plusDays(1),day.plusDays(1)).get("amount"));}
    @Test void unidentifiedHistoryBlocksUnitPricingInsteadOfSilentlyIgnoringPhysicalGoods(){
        unknownSetup();unknown("unknown",3);start();var snapshot=history.day("client",day.toString());assertEquals(3L,((Number)WarehouseJson.maps(snapshot.get("unidentifiedStock")).get(0).get("onhand")).longValue());assertThrows(WarehouseException.class,()->preview(day,day));assertThrows(IllegalArgumentException.class,()->com.ruoyi.warehouse.billing.BillingCalculator.storageQuote(snapshot));
        f.tx.executeWithoutResult(s->{records.get("locations","hold",true);f.jdbc.update("UPDATE wh_asn_unidentified SET mapped_quantity=3 WHERE id='unknown'");com.ruoyi.warehouse.inventory.UnidentifiedOccupancy.change(f.jdbc,"hold",-3);StorageHistory.unidentified(f.jdbc,"unknown");f.receive("product",3,"location","");});at(sequence(),day,12);money("26",preview(day,day).get("amount"));assertTrue(WarehouseJson.maps(history.day("client",day.toString()).get("unidentifiedStock")).isEmpty());
        assertEquals(2,f.scalar("SELECT COUNT(*) FROM wh_storage_change WHERE sequence_no="+sequence()+" AND entity_kind IN ('inventory','unidentified')"));
    }
    @Test void unknownGoodsOccupyALocationOnceAndHistoricalPeakSurvivesTheirRemoval(){
        unknownSetup();f.client.put("storageType","HighestPointLocationTypeUsage");f.update("client",f.client);start();unknown("unknown",3);at(sequence(),day,9);unknown("unknown2",2);at(sequence(),day,10);
        f.tx.executeWithoutResult(s->{records.get("locations","hold",true);f.jdbc.update("UPDATE wh_asn_unidentified SET returned_quantity=quantity");com.ruoyi.warehouse.inventory.UnidentifiedOccupancy.change(f.jdbc,"hold",-5);StorageHistory.unidentified(f.jdbc,"unknown");StorageHistory.unidentified(f.jdbc,"unknown2");});at(sequence(),day,11);
        money("10",preview(day,day).get("amount"));assertTrue(WarehouseJson.maps(history.day("client",day.toString()).get("unidentifiedStock")).isEmpty());assertEquals(1L,((Number)((Map<?,?>)history.day("client",day.toString()).get("locationPeaks")).get("Quarantine")).longValue());
    }
    @Test void endOfDayLocationPricingIncludesUnidentifiedCustodyWithoutInventingAProduct(){
        unknownSetup();f.client.put("storageType","LocationTypeUsage");f.update("client",f.client);unknown("unknown",3);unknown("unknown2",2);start();money("10",preview(day,day).get("amount"));var snapshot=history.day("client",day.toString());assertEquals(2,WarehouseJson.maps(snapshot.get("unidentifiedStock")).size());assertEquals(1,WarehouseJson.maps(snapshot.get("products")).size());
    }
    void unknownSetup(){f.record("unknownAsn","asns",map("client","client","warehouse","warehouse","reference","UNKNOWN-SYNTHETIC"));f.record("hold","locations",map("name","Unknown hold","warehouse","warehouse","type","Quarantine","custodyOnly",true,"active",true));f.record("holdPrice","locationPrices",map("client","client","locationType","Quarantine","frequency","Daily","cost",7,"active",true));}
    void unknown(String id,int quantity){f.tx.executeWithoutResult(s->{records.get("locations","hold",true);f.jdbc.update("INSERT INTO wh_asn_unidentified(id,asn_id,client_id,warehouse_id,location_id,quantity,observed_description,evidence_reference,reason,actor_id) VALUES(?,'unknownAsn','client','warehouse','hold',?,'Unknown synthetic goods','PHOTO-SYNTHETIC','Synthetic custody',7)",id,quantity);com.ruoyi.warehouse.inventory.UnidentifiedOccupancy.change(f.jdbc,"hold",quantity);StorageHistory.unidentified(f.jdbc,id);});}

}
