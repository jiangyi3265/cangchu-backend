package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ruoyi.warehouse.orders.OrderServiceTest.*;

class ChannelOrderTest {
    OrderServiceTest f;
    @BeforeEach void setup(){f=new OrderServiceTest();f.database();f.record("channel","channels",map("name","Shopify Retail","active",true));}
    Map<String,Object> input(){var body=f.input(1);body.put("channelId","channel");return body;}
    @Test void concurrentCreationReturnsOneLinkedOrder()throws Exception{var body=input();var pool=java.util.concurrent.Executors.newFixedThreadPool(2);try{var work=List.<java.util.concurrent.Callable<Map<String,Object>>>of(()->f.save(null,body),()->f.save(null,body));var results=pool.invokeAll(work);assertEquals(WarehouseJson.write(results.get(0).get()),WarehouseJson.write(results.get(1).get()));}finally{pool.shutdownNow();}assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_reference WHERE target_id='channel'"));}
    @Test void chosenChannelKeepsManualSourceAndPostsItsOwnTierAndCharge(){
        f.record("tier","channelTiers",map("client","client","channel","Shopify Retail","basis","Orders","active",true,"tiers",List.of(map("from",1,"rate",3))));
        f.receive("product",2,"location","");var body=input();var order=f.save(null,body);String id=text(order,"id");assertEquals("MANUAL",order.get("source"));assertEquals("Shopify Retail",order.get("channel"));assertEquals(1,f.scalar("SELECT COUNT(*) FROM wh_reference WHERE owner_id='"+id+"' AND target_id='channel'"));
        f.action(id,"allocate");f.action(id,"pick");var posted=f.action(id,"despatch");assertEquals("TIER_PENDING",posted.get("billingStatus"));assertEquals("tier",f.jdbc.queryForObject("SELECT rule_id FROM wh_finance_period",String.class));assertEquals("Shopify Retail",WarehouseJson.map(f.jdbc.queryForObject("SELECT business_json FROM wh_record WHERE kind='charges'",String.class)).get("channel"));assertEquals(id,f.save(null,body).get("id"));
    }
    @Test void inactiveChannelRejectsNewAssignmentsButExistingOrdersCanFinish(){
        f.receive("product",2,"location","");var body=input();var order=f.save(null,body);f.update("channel",map("name","Shopify Retail","active",false));assertThrows(WarehouseException.class,()->f.save(null,input()));assertEquals(order.get("id"),f.save(null,body).get("id"));String id=text(order,"id");f.action(id,"allocate");f.action(id,"pick");assertEquals("DESPATCHED",f.action(id,"despatch").get("status"));
    }
    @Test void omittedChannelKeepsOriginalSnapshotAndExplicitEmptyReturnsToManual(){
        var order=f.save(null,input());String id=text(order,"id");var edit=f.input(1);edit.put("_version",order.get("_version"));edit.put("reference",order.get("reference"));f.update("channel",map("name","Changed externally","active",false));var kept=f.save(id,edit);assertEquals("Shopify Retail",kept.get("channel"));assertEquals("channel",kept.get("channelId"));edit.put("requestId",UUID.randomUUID().toString());edit.put("_version",kept.get("_version"));edit.put("channelId","");var cleared=f.save(id,edit);assertEquals("Manual",cleared.get("channel"));assertFalse(cleared.containsKey("channelId"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_reference WHERE owner_id='"+id+"' AND target_id='channel'"));
    }
    @Test void channelNameAndIntegrationSourceCannotBeForged(){for(String key:List.of("channel","source","workflow","shippingMode")){var body=input();body.put(key,"external");assertThrows(WarehouseException.class,()->f.save(null,body));}assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));}
    @Test void unknownChannelAndAuditFailureLeaveNoOrderOrRequest(){var invalid=input();invalid.put("channelId","missing");assertThrows(WarehouseException.class,()->f.save(null,invalid));f.jdbc.execute("ALTER TABLE wh_audit ADD CONSTRAINT fail_channel CHECK(action_name<>'CREATE_MANUAL_ORDER')");assertThrows(RuntimeException.class,()->f.save(null,input()));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_record WHERE kind='orders'"));assertEquals(0,f.scalar("SELECT COUNT(*) FROM wh_operation_request"));}
}
