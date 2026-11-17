package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Immutable links; all writers in a supplement family lock its closed root first. */
final class AsnLateSupplement {
    private AsnLateSupplement() { }
    // MySQL DATETIME may be returned as LocalDateTime by getObject(), while H2
    // returns Timestamp. Use the JDBC timestamp conversion on both databases.
    private static final RowMapper<Map<String,Object>> LINK=(rs,n)->{
        var row=new LinkedHashMap<String,Object>();
        for(String field:List.of("child_asn","parent_asn","admin_policy","sku_policy","reason","evidence_json"))row.put(field,rs.getString(field));
        row.put("actor_id",rs.getLong("actor_id"));row.put("created_at",rs.getTimestamp("created_at").toInstant().toString());return row;
    };
    static Map<String,Object> link(JdbcTemplate jdbc,String child){
        var rows=jdbc.query("SELECT * FROM wh_asn_late_link WHERE child_asn=?",LINK,child);
        return rows.isEmpty()?null:rows.get(0);
    }
    static void lockParent(WarehouseRepository records,String child){
        var link=link(records.jdbc(),child);if(link!=null)records.scoped("asns",String.valueOf(link.get("parent_asn")),true);
    }
    static List<Map<String,Object>> children(WarehouseRepository records,String parent,boolean lock){
        var links=records.jdbc().query("SELECT * FROM wh_asn_late_link WHERE parent_asn=? ORDER BY child_asn"+(lock?" FOR UPDATE":""),LINK,parent);
        var out=new ArrayList<Map<String,Object>>();
        for(var link:links){var child=records.scoped("asns",String.valueOf(link.get("child_asn")),lock);var value=projection(link);value.put("reference",child.get("reference"));value.put("status",child.get("status"));value.put("received",InboundService.received(child));value.put("billedSkus",child.getOrDefault("billedSkus",List.of()));out.add(value);}
        return out;
    }
    static Map<String,Long> remaining(Map<String,Object> parent,List<Map<String,Object>> children){
        var result=new LinkedHashMap<String,Long>();InboundService.expected(parent).forEach((id,q)->result.put(id,Math.max(0,q-InboundService.received(parent).getOrDefault(id,0L))));
        for(var child:children){
            if("CANCELLED".equals(child.get("status")))continue;
            var received=InboundService.received(child);
            for(var line:WarehouseJson.maps(child.get("lines"))){String product=WarehouseJson.text(line,"product");long matched=((Number)line.get("matchedShortfall")).longValue();long held="CLOSED".equals(child.get("status"))?Math.min(matched,received.getOrDefault(product,0L)):matched;result.computeIfPresent(product,(id,q)->q-held);}
        }
        if(result.values().stream().anyMatch(q->q<0))throw WarehouseException.conflict("Late supplement allocations exceed original shortfall / 补单关联数量与原单短收不一致");
        return result;
    }
    static Map<String,Object> projection(Map<String,Object> link){
        var evidence=WarehouseJson.map(String.valueOf(link.get("evidence_json")));var out=new LinkedHashMap<String,Object>();
        out.put("id",link.get("child_asn"));out.put("parentAsn",link.get("parent_asn"));out.put("parentReference",evidence.get("parentReference"));out.put("adminPolicy",link.get("admin_policy"));out.put("skuPolicy",link.get("sku_policy"));out.put("reason",link.get("reason"));out.put("actorId",link.get("actor_id"));out.put("createdAt",link.get("created_at"));out.put("lines",evidence.get("lines"));return out;
    }
    static void expose(WarehouseRepository records,Map<String,Object> value,Map<String,Object> out){
        String id=WarehouseJson.text(value,"id");var link=link(records.jdbc(),id);
        if(link!=null){out.put("lateSupplement",projection(link));return;}
        if(!"CLOSED".equals(value.get("status")))return;
        var children=children(records,id,false);out.put("lateRemaining",remaining(value,children));children.forEach(c->c.remove("billedSkus"));out.put("lateChildren",children);
    }
    /** Caller holds root, child, and normal receipt locks; never changes root or client data. */
    record Pricing(Map<String,Object> asn,Map<String,Object> client,Map<String,Object> evidence) { }
    static Pricing pricing(WarehouseRepository records,String id,Map<String,Object> asn,Map<String,Object> client){
        var pricedAsn=WarehouseJson.copy(asn);var pricedClient=WarehouseJson.copy(client);var result=new LinkedHashMap<String,Object>();result.put("asn",pricedAsn);result.put("client",pricedClient);
        var link=link(records.jdbc(),id);if(link==null)return new Pricing(pricedAsn,pricedClient,result);
        result.put("lateSupplement",projection(link));result.put("configuredClient",WarehouseJson.copy(client));
        if("included".equals(link.get("admin_policy")))pricedClient.put("asnAdmin",0);
        if("group".equals(link.get("sku_policy"))){
            var parent=records.scoped("asns",String.valueOf(link.get("parent_asn")),true);var billed=new LinkedHashSet<Object>((List<?>)parent.getOrDefault("billedSkus",List.of()));var sources=new ArrayList<Map<String,Object>>();sources.add(Map.of("asn",parent.get("id"),"billedSkus",new ArrayList<>(billed)));
            for(var child:children(records,String.valueOf(link.get("parent_asn")),true))if("group".equals(child.get("skuPolicy"))){var skus=(List<?>)child.get("billedSkus");billed.addAll(skus);sources.add(Map.of("asn",child.get("id"),"billedSkus",skus));}
            pricedAsn.put("billedSkus",new ArrayList<>(billed));result.put("skuBillingSources",sources);
        }
        return new Pricing(pricedAsn,pricedClient,result);
    }
}
