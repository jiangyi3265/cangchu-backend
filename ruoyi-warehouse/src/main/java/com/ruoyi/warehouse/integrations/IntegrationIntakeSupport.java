package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IntegrationIntakeSupport {
    final WarehouseRepository records;
    final WarehouseScope scope;
    final JdbcTemplate jdbc;
    final IntegrationTransport transport;
    final IntegrationVault vault;
    public IntegrationIntakeSupport(WarehouseRepository records,WarehouseScope scope,IntegrationTransport transport,IntegrationVault vault) {
        this.records=records;this.scope=scope;this.jdbc=records.jdbc();this.transport=transport;this.vault=vault;
    }
    Map<String,Object> connection(String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM wh_integration_connection WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())throw WarehouseException.missing();
        var row=rows.get(0);scope.assertRecord("integrationConnections",Map.of("client",row.get("client_id"),"warehouse",row.get("warehouse_id")));return row;
    }
    void permission(String action,Map<String,Object> connection) {
        permission(action,connection,true);
    }
    void permission(String action,Map<String,Object> connection,boolean current) {
        long actor=scope.actor();
        if(safeUser(actor,current).isEmpty())throw WarehouseException.forbidden();
        if(actor!=1) {
            var grants=jdbc.queryForList("SELECT ur.role_id FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms=?"+(current?" FOR UPDATE":""),Long.class,actor,action);
            var scopes=jdbc.queryForList("SELECT scope_type FROM wh_user_scope WHERE user_id=? AND ((scope_type='CLIENT' AND scope_id=?) OR (scope_type='WAREHOUSE' AND scope_id=?))"+(current?" FOR UPDATE":""),String.class,actor,connection.get("client_id"),connection.get("warehouse_id"));
            if(grants.isEmpty()||!scopes.containsAll(List.of("CLIENT","WAREHOUSE")))throw WarehouseException.forbidden();
        }
    }
    private List<Long> safeUser(long actor,boolean current){return jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'"+(current?" FOR UPDATE":""),Long.class,actor);}
    void enabled(Map<String,Object> connection) {
        if(!flag(connection.get("enabled")))throw new IntegrationFailure("CONNECTION_DISABLED");
        for(var ref:Map.of("clients","client_id","warehouses","warehouse_id","channels","channel_id").entrySet()) {
            var row=records.get(ref.getKey(),(String)connection.get(ref.getValue()),false);
            if(inactive(row)||WarehouseJson.enabled(row.get("onStop")))throw new IntegrationFailure("ROUTING_INACTIVE");
        }
    }
    Map<String,Object> authority(String id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM wh_integration_authority WHERE connection_id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty())return Map.of();var row=new LinkedHashMap<>(rows.get(0));row.put("cutover_at",IntegrationService.utc(row.get("cutover_at")));row.put("updated_at",IntegrationService.utc(row.get("updated_at")));row.put("active",flag(row.get("active")));return row;
    }
    Map<String,Object> inbox(String connection,String item,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM wh_integration_inbox WHERE connection_id=? AND id=?"+(lock?" FOR UPDATE":""),connection,item);
        if(rows.isEmpty())throw WarehouseException.missing();return rows.get(0);
    }
    Map<String,Object> inspect(Map<String,Object> connection,Map<String,Object> expected,Map<String,Object> authority) {
        enabled(connection);
        if(authority.isEmpty()||!flag(authority.get("active")))throw new IntegrationFailure("SOURCE_AUTHORITY_REQUIRED");
        if(Instant.parse((String)expected.get("placedAt")).isBefore(Instant.parse((String)authority.get("cutover_at"))))throw new IntegrationFailure("ORDER_BEFORE_CUTOVER");
        var credentials=vault.open((String)connection.get("id"),(String)connection.get("credentials_cipher"));
        return WooCommerceVerification.inspect(transport.reader((String)connection.get("site_url"),credentials,Instant.now().plusSeconds(45)),expected,(String)authority.get("fulfillment_mode"));
    }
    Map<String,Object> product(String id,String client,boolean lock) {
        var product=records.scoped("products",id,lock);
        if(!client.equals(product.get("client")))throw new IntegrationFailure("MAPPING_CLIENT_MISMATCH");
        if(inactive(product)||List.of("bundle","dropship","infiniteStock","discontinued").stream().anyMatch(k->WarehouseJson.enabled(product.get(k))))throw new IntegrationFailure("MAPPING_PRODUCT_INACTIVE");
        return product;
    }
    Map<String,Object> replay(String request,String type,String hash,boolean lock) {
        if(lock)jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,type,hash,"{}");
        var rows=jdbc.queryForList("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=?"+(lock?" FOR UPDATE":""),scope.actor(),request);
        if(rows.isEmpty())return null;var row=rows.get(0);
        if(!type.equals(row.get("operation_type"))||!hash.equals(row.get("request_hash")))throw WarehouseException.conflict("Request ID content changed / 原请求内容已改变");
        var result=WarehouseJson.map((String)row.get("response_json"));if(Boolean.TRUE.equals(result.get("requestCancelled")))throw new IntegrationFailure("ORIGINAL_REQUEST_CANCELLED");return result.isEmpty()?null:result;
    }
    Map<String,Object> complete(String request,Map<String,Object> result) {
        if(jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=?",WarehouseJson.write(result),scope.actor(),request)!=1)throw WarehouseException.conflict("Request journal changed");return result;
    }
    static String text(Map<String,Object> input,String key,int max) {
        if(!(input.get(key) instanceof String value)||value.isBlank()||value.length()>max||value.chars().anyMatch(c->c<32||c==127))throw WarehouseException.invalid("Invalid "+key);
        return value.strip();
    }
    static void keys(Map<String,Object> input,Set<String> allowed) {
        if(input==null||!allowed.containsAll(input.keySet())||WarehouseJson.write(input).length()>8192)throw WarehouseException.invalid("Invalid integration request");
    }
    static long version(Object value){return WarehouseJson.integer(value,"version",0,Long.MAX_VALUE);}
    static boolean flag(Object value){return Boolean.TRUE.equals(value)||value instanceof Number number&&number.intValue()==1;}
    static boolean inactive(Map<String,Object> row){return Boolean.FALSE.equals(row.get("active"))||"N".equals(row.get("active"));}
    static String digest(String connection,Map<String,Object> input){return IntegrationService.hash(IntegrationService.canonical(Map.of("connection",connection,"input",input)));}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> row))throw new IntegrationFailure("SOURCE_EVIDENCE_INVALID");return (Map<String,Object>)row;}
}
