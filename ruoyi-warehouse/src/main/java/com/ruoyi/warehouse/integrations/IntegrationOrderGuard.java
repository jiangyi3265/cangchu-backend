package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class IntegrationOrderGuard {
    private final IntegrationIntakeSupport support;
    private final IntegrationJobLock locks;
    public IntegrationOrderGuard(IntegrationIntakeSupport support,IntegrationJobLock locks){this.support=support;this.locks=locks;}

    public void check(Map<String,Object> order,String action) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Source verification requires the order transaction");
        var rows=support.jdbc.queryForList("SELECT * FROM wh_integration_order WHERE order_id=?",order.get("id"));
        if(rows.size()!=1)throw new IntegrationFailure("SOURCE_LINK_MISSING");
        var link=rows.get(0);String connection=(String)link.get("connection_id");
        var lease=locks.acquire(connection);
        // Keep source configuration stable until the warehouse transaction has committed or rolled back.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){lease.close();}});
        String permission=action.startsWith("warehouse:")?action:"warehouse:orders:"+action;
        var c=support.connection(connection,true);support.permission(permission,c,false);
        if(!Objects.equals(c.get("client_id"),order.get("client"))||!Objects.equals(c.get("warehouse_id"),order.get("warehouse")))throw new IntegrationFailure("SOURCE_LINK_MISSING");
        var authority=support.authority(connection,true);
        if(!Objects.equals(authority.get("source_key"),link.get("source_key")))throw new IntegrationFailure("SOURCE_AUTHORITY_REQUIRED");
        var evidence=WarehouseJson.map((String)link.get("evidence_json"));var original=IntegrationIntakeSupport.object(evidence.get("remote"));
        if("scan".equals(action)) {
            // Unit scanning stays local. The completed pick and packing recheck the provider.
            support.enabled(c);if(!IntegrationIntakeSupport.flag(authority.get("active")))throw new IntegrationFailure("SOURCE_AUTHORITY_REQUIRED");
            support.permission("warehouse:orders:scan",c);return;
        }
        var checked=support.inspect(c,IntegrationIntakeSupport.object(original.get("order")),authority);
        if(!IntegrationService.canonical(checked.get("lines")).equals(IntegrationService.canonical(original.get("lines"))))throw new IntegrationFailure("SOURCE_PRODUCT_CHANGED");
        support.permission(permission,support.connection(connection,false));
        order.put("lastSourceCheckAt",Instant.now().toString());
    }

    public void validateClient(Map<String,Object> order,Map<String,Object> currentClient) {
        // Called with the order context's current, locked client after its normal product-first lock order.
        var rows=support.jdbc.queryForList("SELECT evidence_json FROM wh_integration_order WHERE order_id=?",order.get("id"));
        if(rows.size()!=1)throw new IntegrationFailure("SOURCE_LINK_MISSING");
        var evidence=WarehouseJson.map((String)rows.get(0).get("evidence_json"));
        var original=IntegrationIntakeSupport.object(IntegrationIntakeSupport.object(evidence.get("remote")).get("order"));
        if(!Objects.equals(currentClient.get("currency"),original.get("currency")))throw new IntegrationFailure("SOURCE_CURRENCY_MISMATCH");
    }
}
