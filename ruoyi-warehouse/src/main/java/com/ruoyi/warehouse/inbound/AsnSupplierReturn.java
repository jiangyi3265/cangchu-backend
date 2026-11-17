package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Quantity reservations and immutable supplier destinations, scoped by their owning ASN. */
final class AsnSupplierReturn {
    private AsnSupplierReturn(){ }
    static long reserved(JdbcTemplate jdbc,String task){return jdbc.queryForList("SELECT quantity-handed_quantity-cancelled_quantity FROM wh_asn_return_approval WHERE custody_id=? FOR UPDATE",Long.class,task).stream().mapToLong(Long::longValue).sum();}
    static Map<String,Object> approval(JdbcTemplate jdbc,String task,String id){
        var rows=jdbc.queryForList("SELECT * FROM wh_asn_return_approval WHERE id=? AND custody_id=? FOR UPDATE",id,task);
        if(rows.size()!=1)throw WarehouseException.missing();return rows.get(0);
    }
    static long remaining(Map<String,Object> row){return number(row,"quantity")-number(row,"handed_quantity")-number(row,"cancelled_quantity");}
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static List<Map<String,Object>> view(JdbcTemplate jdbc,String asn){
        return jdbc.query("SELECT a.*,c.product_id,p.display_name sku FROM wh_asn_return_approval a JOIN wh_asn_custody c ON c.id=a.custody_id JOIN wh_record p ON p.id=c.product_id WHERE c.asn_id=? ORDER BY a.created_at,a.id",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();
            for(String[] pair:List.of(new String[]{"id","id"},new String[]{"custody_id","task"},new String[]{"product_id","product"},new String[]{"sku","sku"},new String[]{"recipient","recipient"},new String[]{"destination","destination"},new String[]{"reference","reference"},new String[]{"reason","reason"}))row.put(pair[1],rs.getString(pair[0]));
            long quantity=rs.getLong("quantity"),handed=rs.getLong("handed_quantity"),cancelled=rs.getLong("cancelled_quantity");
            row.put("quantity",quantity);row.put("handed",handed);row.put("cancelled",cancelled);row.put("remaining",quantity-handed-cancelled);row.put("actorId",rs.getLong("actor_id"));row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());row.put("evidence",WarehouseJson.map(rs.getString("evidence_json")));return row;
        },asn);
    }
}
