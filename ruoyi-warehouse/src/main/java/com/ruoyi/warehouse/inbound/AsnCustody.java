package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.support.WarehouseException;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only task/evidence projections shared by ordinary and staged receiving. */
final class AsnCustody {
    private AsnCustody() { }
    static long count(JdbcTemplate jdbc,String asn){return jdbc.queryForList("SELECT id FROM wh_asn_custody WHERE asn_id=? FOR UPDATE",String.class,asn).size()+AsnUnidentified.count(jdbc,asn);}
    static long arrivedUnits(JdbcTemplate jdbc,String asn){return jdbc.queryForList("SELECT c.quantity FROM wh_asn_custody c WHERE c.asn_id=? AND NOT EXISTS(SELECT 1 FROM wh_asn_unidentified_event e WHERE e.custody_id=c.id) FOR UPDATE",Long.class,asn).stream().mapToLong(Long::longValue).sum()+jdbc.queryForList("SELECT quantity FROM wh_asn_unidentified WHERE asn_id=? FOR UPDATE",Long.class,asn).stream().mapToLong(Long::longValue).sum();}
    static void requireEmpty(JdbcTemplate jdbc,String asn){
        if(AsnUnidentified.pending(jdbc,asn)>0||!jdbc.queryForList("SELECT id FROM wh_asn_custody WHERE asn_id=? AND disposed_quantity<quantity FOR UPDATE",String.class,asn).isEmpty())
            throw WarehouseException.conflict("Resolve custody tasks before direct receipt or closure / 请先完成待检、上架或报废任务，再直接收货或结案");
    }
    /** Reserve enough document capacity to finish every physical task already accepted. */
    static void requireCompletionCapacity(JdbcTemplate jdbc,String id,Map<String,Object> asn){
        var pending=jdbc.queryForList("SELECT id,quantity-disposed_quantity remaining,inspection,approved_extra FROM wh_asn_custody WHERE asn_id=? AND disposed_quantity<quantity FOR UPDATE",id);
        long receipts=jdbc.queryForList("SELECT id FROM wh_asn_receipt WHERE asn_id=? FOR UPDATE",String.class,id).size();
        long lines=jdbc.queryForList("SELECT l.id FROM wh_asn_receipt_line l JOIN wh_asn_receipt r ON r.id=l.receipt_id WHERE r.asn_id=? FOR UPDATE",String.class,id).size();
        long events=jdbc.queryForList("SELECT e.id FROM wh_asn_custody_event e JOIN wh_asn_custody c ON c.id=e.custody_id WHERE c.asn_id=? FOR UPDATE",String.class,id).size();
        long units=InboundService.received(asn).values().stream().mapToLong(Long::longValue).sum(),reservedEvents=0;
        for(var task:pending){
            long remaining=((Number)task.get("remaining")).longValue();units+=remaining;
            var returns=jdbc.queryForList("SELECT quantity-handed_quantity-cancelled_quantity FROM wh_asn_return_approval WHERE custody_id=? AND quantity>handed_quantity+cancelled_quantity FOR UPDATE",Long.class,task.get("id"));
            long reserved=returns.stream().mapToLong(Long::longValue).sum();if(reserved>remaining)throw WarehouseException.conflict("Supplier return reservations exceed custody");
            // Every unused approval may be cancelled. Reserve the ordinary completion
            // path as well, so event limits never force a physical supplier handover.
            reservedEvents+=returns.size();reservedEvents+=switch(String.valueOf(task.get("inspection"))){case "PENDING"->3;case "PASS"->((Number)task.get("approved_extra")).longValue()>=remaining?1:2;default->1;};
        }
        var unknown=jdbc.queryForList("SELECT quantity-mapped_quantity-returned_quantity-scrapped_quantity FROM wh_asn_unidentified WHERE asn_id=? AND quantity>mapped_quantity+returned_quantity+scrapped_quantity FOR UPDATE",Long.class,id);units+=unknown.stream().mapToLong(Long::longValue).sum();reservedEvents+=3L*unknown.size();
        if(receipts+pending.size()+unknown.size()>250||lines+pending.size()+unknown.size()>2000||events+reservedEvents>4000||units>10_000_000)
            throw WarehouseException.conflict("Keep enough ASN capacity to finish open custody tasks; reduce splitting or use another forecast / 请为未完成保管任务留足单据容量，减少拆分次数或另建预报");
    }
    static List<Map<String,Object>> tasks(JdbcTemplate jdbc,Map<String,Object> asn){
        var expected=InboundService.expected(asn);var received=InboundService.received(asn);var reservations=new HashMap<String,Long>();
        for(var approval:AsnSupplierReturn.view(jdbc,String.valueOf(asn.get("id"))))reservations.merge(String.valueOf(approval.get("task")),((Number)approval.get("remaining")).longValue(),Long::sum);
        return jdbc.query("SELECT c.*,p.display_name sku,l.display_name location_name FROM wh_asn_custody c JOIN wh_record p ON p.id=c.product_id JOIN wh_record l ON l.id=c.location_id WHERE c.asn_id=? ORDER BY c.received_at,c.id",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();
            for(String[] pair:List.of(new String[]{"id","id"},new String[]{"product_id","product"},new String[]{"location_id","location"},new String[]{"layer_id","layerId"},new String[]{"sku","sku"},new String[]{"location_name","locationName"},new String[]{"inspection","inspection"},new String[]{"reason","reason"},new String[]{"batch_number","batch"},new String[]{"serial_number","serial"},new String[]{"expiry_date","expiry"}))row.put(pair[1],rs.getString(pair[0]));
            long quantity=rs.getLong("quantity"),disposed=rs.getLong("disposed_quantity"),accepted=rs.getLong("accepted_quantity"),remaining=quantity-disposed;
            long returned=rs.getLong("returned_quantity");row.put("quantity",quantity);row.put("remaining",remaining);row.put("accepted",accepted);row.put("returned",returned);row.put("returnReserved",reservations.getOrDefault(rs.getString("id"),0L));row.put("scrapped",disposed-accepted-returned);row.put("approvedExtra",rs.getLong("approved_extra"));
            row.put("overageRequired",Math.max(0,remaining-Math.max(0,expected.getOrDefault(rs.getString("product_id"),0L)-received.getOrDefault(rs.getString("product_id"),0L))));
            row.put("receivedBy",rs.getLong("received_by"));row.put("receivedAt",rs.getTimestamp("received_at").toInstant().toString());return row;
        },asn.get("id"));
    }
    static List<Map<String,Object>> events(JdbcTemplate jdbc,String asn){
        return jdbc.query("SELECT e.*,c.product_id,p.display_name sku,l.display_name location_name FROM wh_asn_custody_event e JOIN wh_asn_custody c ON c.id=e.custody_id JOIN wh_record p ON p.id=c.product_id LEFT JOIN wh_record l ON l.id=e.location_id WHERE c.asn_id=? ORDER BY e.created_at,e.id",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();
            for(String[] pair:List.of(new String[]{"id","id"},new String[]{"custody_id","task"},new String[]{"action_name","action"},new String[]{"reason","reason"},new String[]{"sku","sku"},new String[]{"location_name","locationName"}))row.put(pair[1],rs.getString(pair[0]));
            row.put("quantity",rs.getLong("quantity"));row.put("actorId",rs.getLong("actor_id"));row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());row.put("result",rs.getString("result_json")==null?Map.of():WarehouseJson.map(rs.getString("result_json")));return row;
        },asn);
    }
}
