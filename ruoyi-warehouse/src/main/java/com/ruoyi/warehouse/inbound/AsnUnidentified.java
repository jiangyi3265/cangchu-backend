package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class AsnUnidentified {
    private AsnUnidentified() { }
    static long count(JdbcTemplate jdbc,String asn){return jdbc.queryForList("SELECT id FROM wh_asn_unidentified WHERE asn_id=? FOR UPDATE",String.class,asn).size();}
    static long pending(JdbcTemplate jdbc,String asn){return jdbc.queryForList("SELECT quantity-mapped_quantity-returned_quantity-scrapped_quantity FROM wh_asn_unidentified WHERE asn_id=? FOR UPDATE",Long.class,asn).stream().mapToLong(Long::longValue).sum();}
    static long reserved(JdbcTemplate jdbc,String arrival){return jdbc.queryForList("SELECT quantity-executed_quantity-cancelled_quantity FROM wh_asn_identity_approval WHERE arrival_id=? FOR UPDATE",Long.class,arrival).stream().mapToLong(Long::longValue).sum();}
    static long remaining(Map<String,Object> row){return number(row,"quantity")-number(row,"mapped_quantity")-number(row,"returned_quantity")-number(row,"scrapped_quantity");}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static void capacity(JdbcTemplate jdbc,String asn){
        var arrivals=jdbc.queryForList("SELECT id FROM wh_asn_unidentified WHERE asn_id=? AND quantity>mapped_quantity+returned_quantity+scrapped_quantity FOR UPDATE",String.class,asn);
        long approvals=0;for(String id:arrivals)approvals+=jdbc.queryForList("SELECT id FROM wh_asn_identity_approval WHERE arrival_id=? AND quantity>executed_quantity+cancelled_quantity FOR UPDATE",String.class,id).size();
        long events=jdbc.queryForList("SELECT e.id FROM wh_asn_unidentified_event e JOIN wh_asn_unidentified a ON a.id=e.arrival_id WHERE a.asn_id=? FOR UPDATE",String.class,asn).size();
        if(events+approvals+2L*arrivals.size()>2000)throw WarehouseException.conflict("Keep capacity to resolve held unidentified goods / 请为未识别实物保留完成处理所需的单据容量");
    }
    static void expose(JdbcTemplate jdbc,String asn,Map<String,Object> out){
        var arrivals=jdbc.query("SELECT a.*,l.display_name location_name FROM wh_asn_unidentified a JOIN wh_record l ON l.id=a.location_id WHERE a.asn_id=? ORDER BY a.arrived_at,a.id",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();for(String[] pair:List.of(new String[]{"id","id"},new String[]{"location_id","location"},new String[]{"location_name","locationName"},new String[]{"observed_barcode","barcode"},new String[]{"observed_description","description"},new String[]{"evidence_reference","evidenceReference"},new String[]{"batch_number","batch"},new String[]{"serial_number","serial"},new String[]{"expiry_date","expiry"},new String[]{"reason","reason"}))row.put(pair[1],rs.getString(pair[0]));
            long q=rs.getLong("quantity"),mapped=rs.getLong("mapped_quantity"),returned=rs.getLong("returned_quantity"),scrapped=rs.getLong("scrapped_quantity");row.put("quantity",q);row.put("mapped",mapped);row.put("returned",returned);row.put("scrapped",scrapped);row.put("remaining",q-mapped-returned-scrapped);row.put("arrivedAt",rs.getTimestamp("arrived_at").toInstant().toString());row.put("actorId",rs.getLong("actor_id"));return row;
        },asn);
        var approvals=jdbc.query("SELECT p.*,r.display_name sku FROM wh_asn_identity_approval p JOIN wh_asn_unidentified a ON a.id=p.arrival_id LEFT JOIN wh_record r ON r.id=p.product_id WHERE a.asn_id=? ORDER BY p.created_at,p.id",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();for(String[] pair:List.of(new String[]{"id","id"},new String[]{"arrival_id","arrival"},new String[]{"decision","decision"},new String[]{"product_id","product"},new String[]{"sku","sku"},new String[]{"recipient","recipient"},new String[]{"destination","destination"},new String[]{"reference","reference"},new String[]{"reason","reason"},new String[]{"batch_number","batch"},new String[]{"serial_number","serial"},new String[]{"expiry_date","expiry"}))row.put(pair[1],rs.getString(pair[0]));
            long q=rs.getLong("quantity"),executed=rs.getLong("executed_quantity"),cancelled=rs.getLong("cancelled_quantity");row.put("quantity",q);row.put("executed",executed);row.put("cancelled",cancelled);row.put("remaining",q-executed-cancelled);row.put("actorId",rs.getLong("actor_id"));row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());return row;
        },asn);
        for(var arrival:arrivals)arrival.put("reserved",approvals.stream().filter(p->p.get("arrival").equals(arrival.get("id"))).mapToLong(p->number(p,"remaining")).sum());
        out.put("unidentifiedArrivals",arrivals);out.put("identificationApprovals",approvals);
        out.put("unidentifiedEvents",jdbc.query("SELECT e.* FROM wh_asn_unidentified_event e JOIN wh_asn_unidentified a ON a.id=e.arrival_id WHERE a.asn_id=? ORDER BY e.created_at,e.id",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();for(String[] pair:List.of(new String[]{"id","id"},new String[]{"arrival_id","arrival"},new String[]{"approval_id","approval"},new String[]{"action_name","action"},new String[]{"custody_id","custody"},new String[]{"reason","reason"},new String[]{"reference","reference"}))row.put(pair[1],rs.getString(pair[0]));row.put("quantity",rs.getLong("quantity"));row.put("actorId",rs.getLong("actor_id"));row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());row.put("result",WarehouseJson.map(rs.getString("result_json")));return row;
        },asn));
    }
}
