package com.ruoyi.warehouse.data;

import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class WarehouseRepository {
    private final JdbcTemplate jdbc;
    private final WarehouseScope scope;
    private static final RowMapper<Map<String,Object>> ROW=(rs,n)->{
        var value=WarehouseJson.map(rs.getString("business_json"));value.put("id",rs.getString("id"));value.put("_version",rs.getLong("revision"));
        value.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());value.put("updatedAt",rs.getTimestamp("updated_at").toInstant().toString());return value;
    };
    public WarehouseRepository(JdbcTemplate jdbc,WarehouseScope scope){this.jdbc=jdbc;this.scope=scope;}
    public JdbcTemplate jdbc(){return jdbc;}
    public Map<String,Object> get(String kind,String id,boolean lock){
        var rows=jdbc.query("SELECT * FROM wh_record WHERE kind=? AND id=?"+(lock?" FOR UPDATE":""),ROW,kind,id);
        if(rows.isEmpty())throw WarehouseException.missing();return rows.get(0);
    }
    public Map<String,Object> scoped(String kind,String id,boolean lock){var row=get(kind,id,lock);scope.assertRecord(kind,row);return row;}
    public List<Map<String,Object>> all(String kind){return jdbc.query("SELECT * FROM wh_record WHERE kind=? ORDER BY id",ROW,kind);}
    public List<Map<String,Object>> clientRecordsCurrent(String kind,String client){return jdbc.query("SELECT * FROM wh_record WHERE kind=? AND client_id=? ORDER BY id FOR UPDATE",ROW,kind,client);}
    public Map<String,Object> naturalCurrent(String kind,String scopeKey,String natural){var rows=jdbc.query("SELECT * FROM wh_record WHERE kind=? AND scope_key=? AND natural_key=? FOR UPDATE",ROW,kind,scopeKey,natural);if(rows.isEmpty())throw WarehouseException.missing();return rows.get(0);}
    public Map<String,Object> list(String kind,int page,int size,String keyword){
        return list(kind,page,size,keyword,Map.of());
    }
    public Map<String,Object> list(String kind,int page,int size,String keyword,Map<String,String> filters){
        if(page<1||page>100000||size<1||size>200)throw WarehouseException.invalid("Invalid pagination / 分页范围无效");
        String search=keyword==null?"":keyword.strip();if(search.length()>200)throw WarehouseException.invalid("Search text too long");
        var args=new ArrayList<Object>();args.add(kind);String where="r.kind=? AND "+scope.predicate(kind,args);
        for(String key:List.of("client","warehouse")){String value=filters.getOrDefault(key,"");if(!value.isEmpty()){where+=" AND r."+key+"_id=?";args.add(value);}}
        if(!search.isEmpty()){where+=" AND (LOCATE(?,r.display_name)>0 OR LOCATE(?,r.natural_key)>0)";args.add(search);args.add(search);}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM wh_record r WHERE "+where,Long.class,args.toArray());
        args.add(size);args.add((long)(page-1)*size);
        var rows=jdbc.query("SELECT r.* FROM wh_record r WHERE "+where+" ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",ROW,args.toArray());
        return Map.of("code",200,"msg","success","rows",rows,"total",total==null?0:total);
    }
    public long count(String kind){var args=new ArrayList<Object>();args.add(kind);String where=scope.predicate(kind,args);return jdbc.queryForObject("SELECT COUNT(*) FROM wh_record r WHERE r.kind=? AND "+where,Long.class,args.toArray());}
    public Map<String,Object> write(String kind,String id,Map<String,Object> data,Long version,String natural,String scopeKey){
        var labelFields="returns".equals(kind)?List.of("reference","sku","name"):List.of("shortName","sku","reference","name","description");
        String label=labelFields.stream().map(k->WarehouseJson.text(data,k)).filter(x->!x.isBlank()).findFirst().orElse(id);
        String client=WarehouseJson.text(data,"client"),warehouse=WarehouseJson.text(data,"warehouse");
        if(version==null)jdbc.update("INSERT INTO wh_record(id,kind,client_id,warehouse_id,scope_key,natural_key,display_name,business_json,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?)",id,kind,client,warehouse,scopeKey,natural,label,WarehouseJson.write(data),scope.actor(),scope.actor());
        else if(jdbc.update("UPDATE wh_record SET client_id=?,warehouse_id=?,scope_key=?,natural_key=?,display_name=?,business_json=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE kind=? AND id=? AND revision=?",client,warehouse,scopeKey,natural,label,WarehouseJson.write(data),scope.actor(),kind,id,version)!=1)throw WarehouseException.conflict("Record changed; reload before saving / 数据已更新，请刷新后重试");
        com.ruoyi.warehouse.finance.StorageHistory.record(jdbc,kind,id);
        return get(kind,id,false);
    }
    public void replaceReferences(String id,Map<String,String> refs){
        jdbc.update("DELETE FROM wh_reference WHERE owner_id=?",id);
        for(var ref:refs.entrySet())jdbc.update("INSERT INTO wh_reference(owner_id,target_id,field_name) VALUES(?,?,?)",id,ref.getValue(),ref.getKey());
    }
    public void audit(String action,String kind,String id,Object before,Object after){jdbc.update("INSERT INTO wh_audit(actor_id,action_name,record_id,record_kind,before_json,after_json) VALUES(?,?,?,?,?,?)",scope.actor(),action,id,kind,before==null?null:WarehouseJson.write(before),after==null?null:WarehouseJson.write(after));}
    public void delete(String kind,String id,long version){if(jdbc.update("DELETE FROM wh_record WHERE kind=? AND id=? AND revision=?",kind,id,version)!=1)throw WarehouseException.conflict("Record changed / 数据已更新");com.ruoyi.warehouse.finance.StorageHistory.record(jdbc,kind,id);}
    public boolean stockExists(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM wh_inventory WHERE (product_id=? OR location_id=? OR warehouse_id=? OR client_id=?) AND (on_hand<>0 OR reserved<>0)",Long.class,id,id,id,id)>0||jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_unidentified WHERE (location_id=? OR warehouse_id=? OR client_id=?) AND quantity>mapped_quantity+returned_quantity+scrapped_quantity",Long.class,id,id,id)>0;}
    public boolean inventoryHistory(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM wh_inventory WHERE product_id=? OR location_id=? OR warehouse_id=? OR client_id=?",Long.class,id,id,id,id)>0||jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_unidentified WHERE location_id=? OR warehouse_id=? OR client_id=?",Long.class,id,id,id)>0;}
}
