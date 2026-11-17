package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Follow audited native transfer pairs, never infer a receipt from a shared lot/origin. */
final class ReceiptStockGraph {
    private final WarehouseRepository records;private final WarehouseScope scope;private final JdbcTemplate jdbc;
    ReceiptStockGraph(WarehouseRepository records,WarehouseScope scope){this.records=records;this.scope=scope;this.jdbc=records.jdbc();}
    Map<String,Object> read(Map<String,Object> asn,String lineId){
        var lines=jdbc.query("SELECT l.*,r.asn_id,r.received_at FROM wh_asn_receipt_line l JOIN wh_asn_receipt r ON r.id=l.receipt_id WHERE l.id=? AND r.asn_id=?",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();for(String key:List.of("id","receipt_id","product_id","location_id","inventory_id","layer_id","batch_number","serial_number","expiry_date"))row.put(key,rs.getString(key));row.put("quantity",rs.getLong("quantity"));row.put("receivedAt",rs.getTimestamp("received_at").toInstant().toString());return row;
        },lineId,asn.get("id"));
        if(lines.size()!=1)throw WarehouseException.missing();var receipt=lines.get(0);String product=text(receipt,"product_id"),client=text(asn,"client");
        var owner=records.scoped("products",product,true);if(!client.equals(owner.get("client")))throw WarehouseException.forbidden();records.scoped("clients",client,true);
        var pending=new ArrayDeque<String>();pending.add(text(receipt,"layer_id"));var visited=new LinkedHashMap<String,Map<String,Object>>();var edges=new ArrayList<Map<String,Object>>();
        while(!pending.isEmpty()){
            String layerId=pending.removeFirst();if(visited.containsKey(layerId)||visited.size()>=2000)throw WarehouseException.conflict("Receipt source graph is ambiguous or exceeds review capacity / 收货来源有歧义或超出复核容量，请先核对来源");
            var layers=jdbc.query("SELECT s.*,i.client_id,i.product_id,i.warehouse_id,i.location_id,i.batch_number,i.serial_number,i.expiry_date,i.revision,l.display_name location_name,w.display_name warehouse_name FROM wh_stock_layer s JOIN wh_inventory i ON i.id=s.inventory_id JOIN wh_record l ON l.id=i.location_id JOIN wh_record w ON w.id=i.warehouse_id WHERE s.id=? FOR UPDATE",(rs,n)->{
                var row=new LinkedHashMap<String,Object>();for(String key:List.of("id","inventory_id","origin_id","client_id","product_id","warehouse_id","location_id","batch_number","serial_number","expiry_date","location_name","warehouse_name"))row.put(key,rs.getString(key));row.put("receivedAt",rs.getTimestamp("received_at").toInstant().toString());row.put("remaining",rs.getLong("remaining_quantity"));row.put("reserved",rs.getLong("reserved_quantity"));row.put("poolVersion",rs.getLong("revision"));return row;
            },layerId);
            if(layers.size()!=1)throw WarehouseException.conflict("Receipt source layer is missing / 原收货来源层缺失");var layer=layers.get(0);
            if(!client.equals(layer.get("client_id"))||!product.equals(layer.get("product_id")))throw WarehouseException.conflict("Receipt source ownership mismatch / 收货来源归属不一致");
            if(!scope.granted("WAREHOUSE",text(layer,"warehouse_id")))throw WarehouseException.forbidden();
            for(String key:List.of("batch_number","serial_number","expiry_date"))if(!Objects.equals(receipt.get(key),layer.get(key)))throw WarehouseException.conflict("Receipt tracking evidence changed / 收货批次或追踪资料不一致");
            visited.put(layerId,layer);
            var transfers=jdbc.queryForList("SELECT l.id,l.operation_id,l.quantity_delta,a.after_json FROM wh_ledger l JOIN wh_audit a ON a.record_kind='inventory' AND a.record_id=l.operation_id AND a.action_name='MANUAL_STOCK_TRANSFER' WHERE l.layer_id=? AND l.quantity_delta<0 ORDER BY l.id FOR UPDATE",layerId);
            for(var transfer:transfers){
                var audit=WarehouseJson.map(String.valueOf(transfer.get("after_json")));if(audit==null||!(audit.get("result") instanceof Map<?,?>))throw WarehouseException.conflict("Transfer audit is incomplete / 移库审计证据不完整");var result=WarehouseJson.map(WarehouseJson.write(audit.get("result")));
                if(!"transfer".equals(result.get("action"))||!Objects.equals(transfer.get("operation_id"),result.get("operationId")))throw WarehouseException.conflict("Transfer audit does not match source / 移库审计与来源不一致");
                var next=jdbc.queryForList("SELECT id,layer_id,quantity_delta FROM wh_ledger WHERE operation_id=? AND id>? ORDER BY id LIMIT 1 FOR UPDATE",transfer.get("operation_id"),transfer.get("id"));long quantity=-((Number)transfer.get("quantity_delta")).longValue();
                if(next.size()!=1||quantity!=((Number)next.get(0).get("quantity_delta")).longValue())throw WarehouseException.conflict("Transfer source pair is incomplete / 移库出入来源不完整");
                String child=String.valueOf(next.get(0).get("layer_id"));var declared=WarehouseJson.maps(result.get("layers")).stream().filter(r->child.equals(r.get("id"))).toList();
                if(declared.size()!=1||quantity!=((Number)declared.get(0).get("quantity")).longValue()||!Objects.equals(layer.get("origin_id"),declared.get(0).get("originId"))||!Objects.equals(layer.get("receivedAt"),declared.get(0).get("receivedAt")))throw WarehouseException.conflict("Transfer lineage cannot be verified / 无法核对移库来源关系");
                if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_ledger WHERE layer_id=? AND quantity_delta>0 AND id<?",Long.class,child,next.get(0).get("id"))!=0)throw WarehouseException.conflict("Transfer did not create its destination layer / 移库目标不是新建来源层");
                edges.add(Map.of("parent",layerId,"child",child,"quantity",quantity,"operation",transfer.get("operation_id")));pending.add(child);
            }
        }
        var root=visited.get(text(receipt,"layer_id"));for(var edge:edges){var child=visited.get(edge.get("child"));if(child==null||!Objects.equals(root.get("origin_id"),child.get("origin_id"))||!Objects.equals(root.get("receivedAt"),child.get("receivedAt")))throw WarehouseException.conflict("Transferred stock origin changed / 移库库存原始库龄发生变化");}
        long corrected=jdbc.queryForList("SELECT quantity FROM wh_asn_correction WHERE receipt_line_id=? AND status='EXECUTED' FOR UPDATE",Long.class,lineId).stream().mapToLong(Long::longValue).sum();long remaining=((Number)receipt.get("quantity")).longValue()-corrected;
        if(remaining<0)throw WarehouseException.conflict("Receipt corrections exceed original quantity / 收货更正超过原数量");
        return Map.of("receipt",receipt,"sku",owner.get("sku"),"productVersion",owner.get("_version"),"corrected",corrected,"uncorrected",remaining,"layers",new ArrayList<>(visited.values()),"transfers",edges);
    }
    private static String text(Map<String,Object> row,String key){return WarehouseJson.text(row,key);}
}
