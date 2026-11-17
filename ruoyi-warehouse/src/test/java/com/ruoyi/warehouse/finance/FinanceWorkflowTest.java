package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceWorkflowTest {
    JdbcTemplate jdbc;TransactionTemplate tx;WarehouseScope scope;WarehouseRepository records;FinanceRuleService rules;FinanceWorkflowService service;
    @BeforeEach void database() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:finance_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000","sa","");jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE wh_record(id VARCHAR(64) PRIMARY KEY,kind VARCHAR(64),client_id VARCHAR(64) DEFAULT '',warehouse_id VARCHAR(64) DEFAULT '',scope_key VARCHAR(140) DEFAULT '',natural_key VARCHAR(191),display_name VARCHAR(255),business_json VARCHAR(2000000),revision BIGINT DEFAULT 1,created_by BIGINT,updated_by BIGINT,created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),UNIQUE(kind,scope_key,natural_key))");
        jdbc.execute("CREATE TABLE wh_reference(owner_id VARCHAR(64) REFERENCES wh_record(id) ON DELETE CASCADE,target_id VARCHAR(64) REFERENCES wh_record(id),field_name VARCHAR(64),PRIMARY KEY(owner_id,target_id,field_name))");
        jdbc.execute("CREATE TABLE wh_user_scope(user_id BIGINT,scope_type VARCHAR(16),scope_id VARCHAR(64))");
        jdbc.execute("CREATE TABLE wh_operation_request(actor_id BIGINT,request_id VARCHAR(64),operation_type VARCHAR(64),request_hash CHAR(64),response_json VARCHAR(4000000),PRIMARY KEY(actor_id,request_id))");
        jdbc.execute("CREATE TABLE wh_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor_id BIGINT,action_name VARCHAR(80),record_id VARCHAR(64),record_kind VARCHAR(64),before_json VARCHAR(2000000),after_json VARCHAR(4000000))");
        String ddl=Files.readString(Path.of("../sql/warehouse_005_finance.sql")).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("\\bJSON\\b","VARCHAR(2000000)");
        for(String statement:ddl.split(";"))if(!statement.isBlank())jdbc.execute(statement);
        StorageSchema.create(jdbc);AdjustmentSchema.create(jdbc);
        scope=mock(WarehouseScope.class);when(scope.admin()).thenReturn(true);when(scope.actor()).thenReturn(7L);when(scope.granted(anyString(),anyString())).thenReturn(true);when(scope.predicate(anyString(),anyList())).thenReturn("1=1");
        records=new WarehouseRepository(jdbc,scope);rules=new FinanceRuleService(records,scope);service=new FinanceWorkflowService(records,scope,rules);
        record("client","clients",map("shortName","Synthetic","currency","GBP","serviceVatRate",20,"tierFrequency","Daily"));record("other","clients",map("shortName","Other","currency","GBP"));record("warehouse","warehouses",map("name","Synthetic warehouse"));record("product","products",map("client","client","sku","SYNTHETIC"));
    }
    @Test void rulesValidateTierStructureAndOptimisticVersionAndReplay() {
        var in=map("requestId",uuid(),"client","client","tiers",List.of(map("from",1,"rate",2),map("from",10,"rate",1)));var saved=tx.execute(s->rules.save("orderTiers",null,in));String id=text(saved,"id");assertEquals(id,text(tx.execute(s->rules.save("orderTiers",null,in)),"id"));
        var updated=new LinkedHashMap<>(in);updated.put("requestId",uuid());updated.put("_version",1);updated.put("minimumCharge",20);assertEquals(2L,((Number)tx.execute(s->rules.save("orderTiers",id,updated)).get("_version")).longValue());assertEquals(2L,((Number)tx.execute(s->rules.save("orderTiers",id,updated)).get("_version")).longValue());updated.put("requestId",uuid());assertThrows(WarehouseException.class,()->tx.execute(s->rules.save("orderTiers",id,updated)));
        for(Object tiers:List.of(List.of(),List.of(map("from",2,"rate",1)),List.of(map("from",1,"rate",-1)),List.of(map("from",1,"rate",1),map("from",1,"rate",2))))assertThrows(WarehouseException.class,()->FinanceRuleService.normalize("orderTiers",map("client","client","tiers",tiers)));
        assertThrows(WarehouseException.class,()->FinanceRuleService.normalize("orderTiers",map("client","client","basis","Units","tiers",List.of(map("from",1,"rate",1)))));
    }
    @Test void recurringCreateAndDeleteReplayDoNotDuplicateRules() {
        var in=recurring();var row=tx.execute(s->rules.save("recurring",null,in));assertEquals(row.get("id"),tx.execute(s->rules.save("recurring",null,in)).get("id"));assertEquals(1,count("SELECT COUNT(*) FROM wh_record WHERE kind='recurring'"));String req=uuid(),id=text(row,"id");tx.executeWithoutResult(s->rules.delete("recurring",id,1,req));tx.executeWithoutResult(s->rules.delete("recurring",id,1,req));assertEquals(0,count("SELECT COUNT(*) FROM wh_record WHERE kind='recurring'"));
    }
    @Test void manualChargeExactReplayAndChangedPayloadConflict() {
        var in=charge("client","12.345");var row=tx.execute(s->service.manualCharge(in));money("12.34",row.get("amount"));assertEquals(row.get("id"),tx.execute(s->service.manualCharge(in)).get("id"));in.put("amount",99);assertThrows(WarehouseException.class,()->tx.execute(s->service.manualCharge(in)));assertEquals(1,count("SELECT COUNT(*) FROM wh_finance_source"));in.put("status","INVOICED");assertThrows(WarehouseException.class,()->tx.execute(s->service.manualCharge(in)));
    }
    @Test void invoiceDraftConfirmUnconfirmAndReinvoicePreserveOriginalLines() {
        var a=tx.execute(s->service.manualCharge(charge("client","0.125")));var b=tx.execute(s->service.manualCharge(charge("client","10")));var in=map("requestId",uuid(),"client","client","chargeIds",List.of(a.get("id"),b.get("id")));var draft=tx.execute(s->service.draft(in));String id=text(draft,"id");money("10.12",draft.get("net"));money("2.02",draft.get("tax"));assertEquals(id,tx.execute(s->service.draft(in)).get("id"));
        var confirm=map("requestId",uuid(),"action","confirm","_version",1);var confirmed=tx.execute(s->service.invoiceAction(id,confirm));assertEquals("CONFIRMED",confirmed.get("status"));assertEquals(2L,((Number)tx.execute(s->service.invoiceAction(id,confirm)).get("_version")).longValue());
        var unconfirm=map("requestId",uuid(),"action","unconfirm","_version",2);assertEquals("UNCONFIRMED",tx.execute(s->service.invoiceAction(id,unconfirm)).get("status"));assertEquals(0,count("SELECT COUNT(*) FROM wh_finance_invoice_claim"));assertEquals(2,count("SELECT COUNT(*) FROM wh_finance_invoice_line"));in.put("requestId",uuid());assertNotEquals(id,tx.execute(s->service.draft(in)).get("id"));assertEquals(4,count("SELECT COUNT(*) FROM wh_finance_invoice_line"));
    }
    @Test void mixedClientDraftFailsAtomicallyWithoutClaimingFirstCharge() {
        var a=tx.execute(s->service.manualCharge(charge("client",5)));var b=tx.execute(s->service.manualCharge(charge("other",5)));String req=uuid();assertThrows(WarehouseException.class,()->tx.execute(s->service.draft(map("requestId",req,"client","client","chargeIds",List.of(a.get("id"),b.get("id"))))));assertEquals(0,count("SELECT COUNT(*) FROM wh_finance_invoice_claim"));assertEquals(0,count("SELECT COUNT(*) FROM wh_record WHERE kind='invoices'"));assertEquals(0,count("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='"+req+"'"));assertEquals("UNCONFIRMED",records.get("charges",text(a,"id"),false).get("status"));
    }
    @Test void alreadyClaimedChargeCannotBeInTwoDraftsAndStaleInvoiceCannotChange() {
        var c=tx.execute(s->service.manualCharge(charge("client",5)));var d=tx.execute(s->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(c.get("id")))));assertThrows(WarehouseException.class,()->tx.execute(s->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(c.get("id"))))));assertThrows(WarehouseException.class,()->tx.execute(s->service.invoiceAction(text(d,"id"),map("requestId",uuid(),"action","confirm","_version",99))));assertEquals("DRAFT",service.invoice(text(d,"id")).get("status"));
    }
    @Test void recurringQuoteHashBindsRuleAndCommitIsAtomicAndRestartReplaySafe() {
        var in=recurring();var r=tx.execute(s->rules.save("recurring",null,in));String day=LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();var args=map("client","client","mode","Recurring","from",day,"to",day);var preview=tx.execute(s->service.preview(args));money("6.00",preview.get("amount"));
        var change=new LinkedHashMap<>(in);change.put("requestId",uuid());change.put("_version",1);change.put("amount",4);tx.execute(s->rules.save("recurring",text(r,"id"),change));var commit=new LinkedHashMap<>(args);commit.put("requestId",uuid());commit.put("quoteHash",preview.get("quoteHash"));assertThrows(WarehouseException.class,()->tx.execute(s->service.commit(commit)));assertEquals(0,count("SELECT COUNT(*) FROM wh_finance_source"));
        commit.put("quoteHash",tx.execute(s->service.preview(args)).get("quoteHash"));var done=tx.execute(s->service.commit(commit));money("8.00",done.get("amount"));assertEquals(done.get("id"),service.run(text(done,"id")).get("id"));assertEquals(1,count("SELECT COUNT(*) FROM wh_record WHERE kind='billingRuns'"));var fresh=new FinanceWorkflowService(records,scope,rules);assertEquals(WarehouseJson.write(done),WarehouseJson.write(tx.execute(s->fresh.commit(commit))));assertEquals(1,count("SELECT COUNT(*) FROM wh_finance_source"));assertTrue(WarehouseJson.maps(tx.execute(s->service.preview(args)).get("entries")).isEmpty());
    }
    @Test void storageHistoricalEvidenceAndIncompletePeriodsAreRejected() {
        String day=LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();assertThrows(WarehouseException.class,()->tx.execute(s->service.preview(map("client","client","mode","Storage","from",day,"to",day))));var r=recurring();r.put("frequency","Monthly");tx.execute(s->rules.save("recurring",null,r));assertThrows(WarehouseException.class,()->tx.execute(s->service.preview(map("client","client","mode","Recurring","from",day,"to",day))));
    }
    @Test void periodRegistrationFreezesRuleAndRejectsOverlappingFrequencyAndRollsBack() {
        var ri=map("requestId",uuid(),"client","client","tiers",List.of(map("from",1,"rate",3)));var rule=tx.execute(s->rules.save("orderTiers",null,ri));record("order","orders",map("client","client","warehouse","warehouse","status","DESPATCHED"));var order=records.get("orders","order",false);order.put("despatchedAt",Instant.now().toString());var client=records.get("clients","client",false);var periods=new FinancePeriodService(records,scope);assertThrows(IllegalStateException.class,()->periods.registerOrder(order,client,rule));String period=tx.execute(s->periods.registerOrder(order,client,rule));
        client.put("tierFrequency","Monthly");record("order2","orders",map("client","client","warehouse","warehouse","status","DESPATCHED"));var other=new LinkedHashMap<>(order);other.put("id","order2");assertThrows(WarehouseException.class,()->tx.execute(s->periods.registerOrder(other,client,rule)));assertEquals(1,count("SELECT COUNT(*) FROM wh_finance_tier_order"));assertNotNull(jdbc.queryForObject("SELECT snapshot_json FROM wh_finance_period WHERE id=?",String.class,period));
    }
    @Test void periodSettlementCountsOrdersNotUnitsAndRejectsPartialScope() {
        String day=LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();var rule=tx.execute(s->rules.save("orderTiers",null,map("requestId",uuid(),"client","client","tiers",List.of(map("from",1,"rate",3),map("from",2,"rate",2)))));String pid="a".repeat(64);jdbc.update("INSERT INTO wh_finance_period(id,rule_id,client_id,from_date,to_date,currency,snapshot_json) VALUES(?,?,?,?,?,?,?)",pid,rule.get("id"),"client",day,day,"GBP",WarehouseJson.write(Map.of("rule",rule,"frequency","Daily")));
        for(String id:List.of("order1","order2")){record(id,"orders",map("client","client","warehouse","warehouse","status","DESPATCHED","quantity",100,"billingStatus","TIER_PENDING"));jdbc.update("INSERT INTO wh_finance_tier_order VALUES(?,?,?)",id,pid,"warehouse");}
        var input=map("client","client","mode","OrderTier","from",day,"to",day);var quote=tx.execute(s->service.preview(input));money("4.00",quote.get("amount"));doThrow(WarehouseException.forbidden()).when(scope).assertRecord(eq("orders"),argThat(r->"order2".equals(r.get("id"))));assertThrows(WarehouseException.class,()->tx.execute(s->service.preview(input)));reset(scope);when(scope.admin()).thenReturn(true);when(scope.actor()).thenReturn(7L);var commit=new LinkedHashMap<>(input);commit.put("requestId",uuid());commit.put("quoteHash",quote.get("quoteHash"));tx.execute(s->service.commit(commit));assertEquals(2,count("SELECT settled_count FROM wh_finance_period"));assertEquals("CHARGED",records.get("orders","order1",false).get("billingStatus"));assertEquals(1,count("SELECT COUNT(*) FROM wh_finance_source"));
    }
    @Test void laterPostingFailureRollsBackEarlierChargesAndRequestJournal() {
        var a=recurring();var b=recurring();b.put("description","Second");tx.execute(s->rules.save("recurring",null,a));tx.execute(s->rules.save("recurring",null,b));String day=LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();var input=map("client","client","mode","Recurring","from",day,"to",day);var quote=tx.execute(s->service.preview(input));var entries=WarehouseJson.maps(quote.get("entries"));String source=text(entries.get(1),"source");jdbc.execute("ALTER TABLE wh_finance_source ADD CONSTRAINT reject_second CHECK(source_key<>'"+source+"')");var commit=new LinkedHashMap<>(input);String request=uuid();commit.put("requestId",request);commit.put("quoteHash",quote.get("quoteHash"));assertThrows(RuntimeException.class,()->tx.execute(s->service.commit(commit)));assertEquals(0,count("SELECT COUNT(*) FROM wh_record WHERE kind='charges'"));assertEquals(0,count("SELECT COUNT(*) FROM wh_finance_source"));assertEquals(0,count("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='"+request+"'"));
    }
    @Test void replayCannotRecoverChargesAfterWarehouseScopeRevocation() {
        var body=charge("client",5);body.put("warehouse","warehouse");var charge=tx.execute(s->service.manualCharge(body));doThrow(WarehouseException.forbidden()).when(scope).assertRecord(eq("warehouses"),anyMap());assertThrows(WarehouseException.class,()->tx.execute(s->service.manualCharge(body)));assertEquals(1,count("SELECT COUNT(*) FROM wh_finance_source"));assertEquals("UNCONFIRMED",charge.get("status"));
    }
    @Test void hugeExponentInputsAreRejectedBeforeDecimalExpansion() {
        for(String amount:List.of("1e1000","1e1000000000","1e-1000","9999999999999999999999.999"))assertThrows(WarehouseException.class,()->FinanceInput.money(amount));
        var in=recurring();in.put("amount","1e1000");assertThrows(WarehouseException.class,()->tx.execute(s->rules.save("recurring",null,in)));assertEquals(0,count("SELECT COUNT(*) FROM wh_record WHERE kind='recurring'"));
    }
    @Test void frequencyChangeCannotOverlapPreviousDayInsideNewMonth() {
        var rule=tx.execute(s->rules.save("orderTiers",null,map("requestId",uuid(),"client","client","tiers",List.of(map("from",1,"rate",1)))));var today=LocalDate.now(ZoneOffset.UTC);var prior=today.getDayOfMonth()>1?today.minusDays(1):today.plusDays(1);String day=prior.toString();
        jdbc.update("INSERT INTO wh_finance_period(id,rule_id,client_id,from_date,to_date,currency,snapshot_json) VALUES(?,?,?,?,?,?,?)","b".repeat(64),rule.get("id"),"client",day,day,"GBP",WarehouseJson.write(Map.of("rule",rule)));
        record("order","orders",map("client","client","warehouse","warehouse","status","DESPATCHED"));var order=records.get("orders","order",false);order.put("despatchedAt",Instant.now().toString());var client=records.get("clients","client",false);client.put("tierFrequency","Monthly");var periodService=new FinancePeriodService(records,scope);
        assertThrows(WarehouseException.class,()->tx.execute(s->periodService.registerOrder(order,client,rule)));assertEquals(0,count("SELECT COUNT(*) FROM wh_finance_tier_order"));
    }
    @Test void twoConcurrentDraftsCannotClaimSameCharge() throws Exception {
        var charge=tx.execute(s->service.manualCharge(charge("client",5)));var executor=java.util.concurrent.Executors.newFixedThreadPool(2);try{
            var calls=new ArrayList<java.util.concurrent.Callable<Boolean>>();for(int i=0;i<2;i++)calls.add(()->{try{tx.execute(t->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(charge.get("id")))));return true;}catch(RuntimeException conflict){return false;}});
            int passed=0;for(var future:executor.invokeAll(calls))if(future.get())passed++;assertEquals(1,passed);assertEquals(1,count("SELECT COUNT(*) FROM wh_finance_invoice_claim"));assertEquals(1,count("SELECT COUNT(*) FROM wh_record WHERE kind='invoices'"));
        }finally{executor.shutdownNow();}
    }
    @Test void alreadyPostedRecurringCandidatesStillHaveBoundedWork() {
        for(int i=0;i<6;i++){var in=recurring();in.put("description","Rule "+i);tx.execute(t->rules.save("recurring",null,in));}
        var limited=spy(jdbc);doReturn(1L).when(limited).queryForObject(eq("SELECT COUNT(*) FROM wh_finance_source WHERE source_key=?"),eq(Long.class),anyString());
        var repo=new WarehouseRepository(limited,scope);var bounded=new FinanceWorkflowService(repo,scope,new FinanceRuleService(repo,scope));var to=LocalDate.now(ZoneOffset.UTC).minusDays(1);var from=to.minusDays(365);
        assertThrows(WarehouseException.class,()->tx.execute(t->bounded.preview(map("client","client","mode","Recurring","from",from.toString(),"to",to.toString()))));verify(limited,times(2000)).queryForObject(eq("SELECT COUNT(*) FROM wh_finance_source WHERE source_key=?"),eq(Long.class),anyString());assertEquals(0,count("SELECT COUNT(*) FROM wh_finance_source"));
    }
    @Test void invoiceTypeExplicitZeroVatOverridesClientDefault() {
        var type=tx.execute(s->rules.save("invoiceTypes",null,map("requestId",uuid(),"name","Zero VAT","chargeType","Additional","vatRate",0,"active",true)));var zero=charge("client",10);zero.put("invoiceType",type.get("id"));var a=tx.execute(s->service.manualCharge(zero));var b=tx.execute(s->service.manualCharge(charge("client",10)));var invoice=tx.execute(s->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(a.get("id"),b.get("id")))));money("20",invoice.get("net"));money("2",invoice.get("tax"));
    }
    @Test void crossWarehouseInvoiceIsExcludedAsWholeFromListCountAndDetail() {
        record("warehouse2","warehouses",map("name","Other warehouse"));var first=charge("client",5);first.put("warehouse","warehouse");var second=charge("client",5);second.put("warehouse","warehouse2");var a=tx.execute(t->service.manualCharge(first));var b=tx.execute(t->service.manualCharge(second));var invoice=tx.execute(t->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(a.get("id"),b.get("id")))));
        jdbc.update("INSERT INTO wh_user_scope VALUES(7,'CLIENT','client')");jdbc.update("INSERT INTO wh_user_scope VALUES(7,'WAREHOUSE','warehouse')");
        var actual=spy(new WarehouseScope(jdbc));doReturn(7L).when(actual).actor();doReturn(false).when(actual).admin();var repo=new WarehouseRepository(jdbc,actual);var scoped=new FinanceWorkflowService(repo,actual,new FinanceRuleService(repo,actual));
        try(var security=mockStatic(com.ruoyi.common.utils.SecurityUtils.class)){security.when(()->com.ruoyi.common.utils.SecurityUtils.hasPermi("warehouse:finance:query")).thenReturn(true);var list=scoped.list("invoices",1,20,"client");assertEquals(0L,list.get("total"));assertTrue(WarehouseJson.maps(list.get("rows")).isEmpty());assertThrows(WarehouseException.class,()->scoped.invoice(text(invoice,"id")));assertEquals(1L,scoped.list("charges",1,20,"client").get("total"));}
    }
    @Test void emptyCustomerDefaultsRetainLegacyGbpAndDailySemantics() {
        jdbc.update("UPDATE wh_record SET business_json=? WHERE id='client'",WarehouseJson.write(map("shortName","Synthetic","currency","","tierFrequency","","serviceVatRate","")));
        var charge=tx.execute(t->service.manualCharge(charge("client",10)));assertEquals("GBP",charge.get("currency"));var invoice=tx.execute(t->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(charge.get("id")))));money("0",invoice.get("tax"));
        var rule=tx.execute(t->rules.save("orderTiers",null,map("requestId",uuid(),"client","client","tiers",List.of(map("from",1,"rate",1)))));record("order","orders",map("client","client","warehouse","warehouse","status","DESPATCHED"));var order=records.get("orders","order",false);order.put("despatchedAt",Instant.now().toString());var periods=new FinancePeriodService(records,scope);String pid=tx.execute(t->periods.registerOrder(order,records.get("clients","client",false),rule));assertEquals("GBP",jdbc.queryForObject("SELECT currency FROM wh_finance_period WHERE id=?",String.class,pid));assertEquals(LocalDate.now(ZoneOffset.UTC),jdbc.queryForObject("SELECT from_date FROM wh_finance_period WHERE id=?",java.sql.Date.class,pid).toLocalDate());
    }
    @Test void financeDtoPreservesDecimalAndLargeIntegerPrecisionWithoutChangingEvidence() {
        var source=map("amount",new BigDecimal("123456789123456789.123456789"),"minimumCharge",123456789123456789L,"_version",2L,"tiers",List.of(map("from",1L,"rate",new BigDecimal("0.000000001"))));var dto=FinanceDto.map(source);assertEquals("123456789123456789.123456789",dto.get("amount"));assertEquals("123456789123456789",dto.get("minimumCharge"));assertEquals(2L,dto.get("_version"));assertEquals("0.000000001",WarehouseJson.maps(dto.get("tiers")).get(0).get("rate"));assertTrue(source.get("amount") instanceof BigDecimal);
    }
    Map<String,Object> recurring(){return map("requestId",uuid(),"client","client","description","Synthetic recurring fee","amount",3,"quantity",2,"frequency","Daily","active",true);}
    Map<String,Object> charge(String client,Object amount){return map("requestId",uuid(),"client",client,"description","Synthetic manual charge","amount",amount);}
    void record(String id,String kind,Map<String,Object> data){tx.executeWithoutResult(status -> records.write(kind,id,data,null,id,WarehouseJson.text(data,"client")));}
    long count(String sql){return jdbc.queryForObject(sql,Long.class);}
    static String uuid(){return UUID.randomUUID().toString();}static String text(Map<String,Object> m,String k){return WarehouseJson.text(m,k);}
    static Map<String,Object> map(Object... values){var m=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)m.put((String)values[i],values[i+1]);return m;}
    static void money(String expected,Object actual){assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())));}
    @Test void invoiceUsesRecordedChargeCurrencyAfterClientCurrencyChanges(){
        var charge=tx.execute(s->service.manualCharge(charge("client",5)));
        tx.executeWithoutResult(s->{var old=records.get("clients","client",true);var data=FinanceInput.business(old);data.put("currency","EUR");records.write("clients","client",data,((Number)old.get("_version")).longValue(),"client","");});
        var other=tx.execute(s->service.manualCharge(charge("client",5)));
        assertThrows(WarehouseException.class,()->tx.execute(s->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(charge.get("id"),other.get("id"))))));
        var invoice=tx.execute(s->service.draft(map("requestId",uuid(),"client","client","chargeIds",List.of(charge.get("id")))));assertEquals("GBP",invoice.get("currency"));
    }

}
