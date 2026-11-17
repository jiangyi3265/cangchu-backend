package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.inventory.InventoryService;
import com.ruoyi.warehouse.orders.*;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.warehouse.finance.FinanceWorkflowTest.*;

/** Actual native receive/create/allocate/pick/despatch fees, not mocked charges. */
class FinanceOrderInvoiceTest {
    FinanceWorkflowTest fixture;
    OrderService orders;
    List<String> chargeIds;

    @BeforeEach void nativeOrderCharges() throws Exception {
        fixture=new FinanceWorkflowTest();fixture.database();
        for(String name:List.of("warehouse_001.sql","warehouse_003_orders.sql")){
            String ddl=Files.readString(Path.of("../sql/"+name)).replaceAll("(?m)^--.*$","").replaceAll(" CHARACTER SET [A-Za-z0-9_]+","").replaceAll(" COLLATE[= ]+[A-Za-z0-9_]+","").replaceAll(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replaceAll("\\bJSON\\b","VARCHAR(2000000)");
            for(String statement:ddl.split(";"))if(!statement.isBlank())fixture.jdbc.execute(statement);
        }
        OrderWorkSchema.create(fixture.jdbc);
        com.ruoyi.warehouse.stocktake.StocktakeSchema.create(fixture.jdbc);
        fixture.jdbc.update("UPDATE wh_record SET business_json=? WHERE id='client'",WarehouseJson.write(map("shortName","Synthetic","currency","GBP","serviceVatRate",20,"firstPick",2,"additionalPick","0.5","packagingFee",1,"orderAdminFee",1,"pickingLogic","FIFO")));
        fixture.record("type","locationTypes",map("name","Pick","active",true,"maxUnits",0,"pickPriority",1));
        fixture.record("location","locations",map("name","A1","warehouse","warehouse","type","Pick","active",true));
        var inventory=new InventoryService(fixture.records,fixture.scope);
        fixture.tx.execute(s->inventory.move(map("requestId",uuid(),"action","add","product","product","warehouse","warehouse","location","location","quantity",20,"reason","Synthetic native finance regression")));
        // Let Spring instantiate the package-private stock engine just as in the application.
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("records",fixture.records);beans.registerSingleton("scope",fixture.scope);
        beans.registerSingleton("periods",new FinancePeriodService(fixture.records,fixture.scope));
        var stockDefinition=new org.springframework.beans.factory.support.RootBeanDefinition();
        stockDefinition.setBeanClassName("com.ruoyi.warehouse.orders.OrderStock");
        stockDefinition.setAutowireMode(org.springframework.beans.factory.support.AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        beans.registerBeanDefinition("orderStock",stockDefinition);
        orders=beans.createBean(OrderService.class);
        despatch(3);
        fixture.tx.execute(s->fixture.rules.save("orderTiers",null,map("requestId",uuid(),"client","client","tiers",List.of(map("from",1,"rate",1)))));
        despatch(1);despatch(2);
        fixture.tx.execute(s->fixture.service.manualCharge(fixture.charge("client","3.75")));
        chargeIds=fixture.jdbc.queryForList("SELECT id FROM wh_record WHERE kind='charges' ORDER BY id",String.class);
        assertEquals(4,chargeIds.size());assertEquals(3,fixture.count("SELECT COUNT(*) FROM wh_order_billing"));
        assertEquals(2,fixture.count("SELECT COUNT(*) FROM wh_finance_tier_order"));
    }

    private void despatch(long quantity){
        var request=map("requestId",uuid(),"client","client","warehouse","warehouse","reference","native-"+uuid(),"lines",List.of(map("product","product","quantity",quantity)),"name","Synthetic recipient","address1","1 Example Street","town","Example","postcode","TEST 1","country","UNITED KINGDOM");
        String id=text(fixture.tx.execute(s->orders.save(null,request)),"id");
        for(String action:List.of("allocate","pick","despatch")){
            var body=map("requestId",uuid(),"action",action);if("despatch".equals(action))body.put("tracking","SYNTHETIC-TRACKING");
            fixture.tx.execute(s->orders.action(id,body));
        }
    }

    @Test void mixedNativeOrderExtrasAndManualFeesKeepEverySnapshotAligned(){
        var input=map("requestId",uuid(),"client","client","chargeIds",chargeIds);
        var invoice=fixture.tx.execute(s->fixture.service.draft(input));
        assertEquals(4,WarehouseJson.maps(invoice.get("lines")).size());
        for(var saved:fixture.jdbc.queryForList("SELECT charge_id,snapshot_json FROM wh_finance_invoice_line ORDER BY charge_id")){
            String id=String.valueOf(saved.get("charge_id"));var snapshot=WarehouseJson.map(String.valueOf(saved.get("snapshot_json")));
            var line=WarehouseJson.map(WarehouseJson.write(snapshot.get("line")));var charge=WarehouseJson.map(WarehouseJson.write(snapshot.get("charge")));
            assertEquals(id,line.get("id"));assertEquals(id,charge.get("id"));
            money(charge.get("amount").toString(),line.get("amount"));
            money(fixture.records.get("charges",id,false).get("amount").toString(),line.get("amount"));
        }
        assertEquals(4,fixture.count("SELECT COUNT(*) FROM wh_finance_invoice_claim"));
        assertEquals(3,fixture.count("SELECT COUNT(*) FROM wh_reference WHERE owner_id='"+invoice.get("id")+"' AND field_name LIKE 'order%'"));
        assertEquals(invoice.get("id"),fixture.tx.execute(s->fixture.service.draft(input)).get("id"));
        money("12.75",invoice.get("net"));money("2.55",invoice.get("tax"));
    }

    @Test void failureAtLastNativeChargeRollsBackInvoiceClaimsSnapshotsAndStatuses(){
        String rejected=chargeIds.get(chargeIds.size()-1),request=uuid();
        fixture.jdbc.execute("ALTER TABLE wh_finance_invoice_line ADD CONSTRAINT reject_last_native_line CHECK(charge_id<>'"+rejected+"')");
        var body=map("requestId",request,"client","client","chargeIds",chargeIds);long ledgerBefore=fixture.count("SELECT COUNT(*) FROM wh_ledger");
        assertThrows(RuntimeException.class,()->fixture.tx.execute(s->fixture.service.draft(body)));
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM wh_record WHERE kind='invoices'"));
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM wh_finance_invoice_claim"));
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM wh_finance_invoice_line"));
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM wh_operation_request WHERE request_id='"+request+"'"));
        for(String id:chargeIds){var charge=fixture.records.get("charges",id,false);assertEquals("UNCONFIRMED",charge.get("status"));assertFalse(charge.containsKey("invoice"));}
        assertEquals(ledgerBefore,fixture.count("SELECT COUNT(*) FROM wh_ledger"));
        fixture.jdbc.execute("ALTER TABLE wh_finance_invoice_line DROP CONSTRAINT reject_last_native_line");
        assertEquals("DRAFT",fixture.tx.execute(s->fixture.service.draft(body)).get("status"));
        assertEquals(4,fixture.count("SELECT COUNT(*) FROM wh_finance_invoice_claim"));
    }
}
