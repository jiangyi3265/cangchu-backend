package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.support.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

/** One atomic final-state history batch per committed business transaction. */
public final class StorageHistory {
    public static final Set<String> KINDS=Set.of("clients","products","warehouses","locations","locationTypes","locationPrices");
    private static final Object KEY=new Object();
    private StorageHistory() { }
    public static void record(JdbcTemplate jdbc,String kind,String id){if(KINDS.contains(kind))touch(jdbc,kind,id);}
    public static void inventory(JdbcTemplate jdbc,String id){touch(jdbc,"inventory",id);}
    public static void unidentified(JdbcTemplate jdbc,String id){touch(jdbc,"unidentified",id);}
    private static void touch(JdbcTemplate jdbc,String kind,String id){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly())throw new IllegalStateException("Storage history requires the owning write transaction");
        Buffer buffer=(Buffer)TransactionSynchronizationManager.getResource(KEY);
        if(buffer==null){buffer=new Buffer(jdbc);TransactionSynchronizationManager.bindResource(KEY,buffer);TransactionSynchronizationManager.registerSynchronization(buffer);}
        if(buffer.jdbc.getDataSource()!=jdbc.getDataSource())throw new IllegalStateException("Mixed storage data sources");buffer.keys.computeIfAbsent(kind,k->new TreeSet<>()).add(id);
    }
    private static final class Buffer implements TransactionSynchronization {
        final JdbcTemplate jdbc;final Map<String,SortedSet<String>> keys=new TreeMap<>();
        Buffer(JdbcTemplate jdbc){this.jdbc=jdbc;}
        @Override public void suspend(){TransactionSynchronizationManager.unbindResource(KEY);}
        @Override public void resume(){TransactionSynchronizationManager.bindResource(KEY,this);}
        @Override public void afterCompletion(int status){if(TransactionSynchronizationManager.getResource(KEY)==this)TransactionSynchronizationManager.unbindResource(KEY);}
        @Override public void beforeCommit(boolean readOnly){
            var control=jdbc.queryForMap("SELECT * FROM wh_storage_control WHERE id=1 FOR UPDATE");if(!Boolean.TRUE.equals(control.get("enabled"))&&!Integer.valueOf(1).equals(control.get("enabled")))return;
            // All touched rows are already locked by this transaction. No other
            // business rows are acquired while holding the short commit barrier.
            append(jdbc,keys,false,((Number)control.get("last_sequence")).longValue()+1);
        }
    }
    static Map<String,Object> append(JdbcTemplate jdbc,Map<String,? extends Set<String>> keys,boolean baseline,long sequence){
        String id=UUID.randomUUID().toString();Timestamp now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
        var last=jdbc.queryForList("SELECT effective_at FROM wh_storage_batch ORDER BY sequence_no DESC LIMIT 1 FOR UPDATE",Timestamp.class);
        if(!last.isEmpty()&&now.before(last.get(0)))throw WarehouseException.conflict("Database clock moved backwards; history cannot be recorded / 数据库时间倒退，暂不能记录可靠历史");
        int count=keys.values().stream().mapToInt(Set::size).sum();jdbc.update("INSERT INTO wh_storage_batch(id,sequence_no,effective_at,baseline,change_count) VALUES(?,?,?,?,?)",id,sequence,now,baseline,count);
        for(var kind:keys.entrySet())for(String key:kind.getValue()){
            Map<String,Object> value="inventory".equals(kind.getKey())?pool(jdbc,key):"unidentified".equals(kind.getKey())?unidentifiedSnapshot(jdbc,key):snapshotRecord(jdbc,kind.getKey(),key);
            String client="clients".equals(kind.getKey())?key:WarehouseJson.text(value,"client");String json=WarehouseJson.write(value);
            jdbc.update("INSERT INTO wh_storage_change(batch_id,sequence_no,entity_kind,entity_id,client_id,payload_json,payload_hash) VALUES(?,?,?,?,?,?,?)",id,sequence,kind.getKey(),key,client,json,FinanceInput.hash(value));
        }
        jdbc.update("UPDATE wh_storage_control SET last_sequence=? WHERE id=1",sequence);
        return Map.of("id",id,"sequence",sequence,"effectiveAt",now.toInstant().toString(),"changes",count);
    }
    private static Map<String,Object> snapshotRecord(JdbcTemplate jdbc,String kind,String id){
        var rows=jdbc.query("SELECT business_json,revision FROM wh_record WHERE kind=? AND id=? FOR UPDATE",(rs,n)->{var row=WarehouseJson.map(rs.getString(1));row.put("id",id);row.put("_version",rs.getLong(2));return row;},kind,id);
        return rows.isEmpty()?Map.of("id",id,"deleted",true):rows.get(0);
    }
    private static Map<String,Object> unidentifiedSnapshot(JdbcTemplate jdbc,String id){
        return jdbc.queryForObject("SELECT * FROM wh_asn_unidentified WHERE id=? FOR UPDATE",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("client",rs.getString("client_id"));row.put("warehouse",rs.getString("warehouse_id"));row.put("location",rs.getString("location_id"));row.put("asn",rs.getString("asn_id"));row.put("onhand",rs.getLong("quantity")-rs.getLong("mapped_quantity")-rs.getLong("returned_quantity")-rs.getLong("scrapped_quantity"));row.put("arrivedAt",rs.getTimestamp("arrived_at").toInstant().toString());row.put("description",rs.getString("observed_description"));return row;
        },id);
    }
    private static Map<String,Object> pool(JdbcTemplate jdbc,String id){
        var rows=jdbc.query("SELECT * FROM wh_inventory WHERE id=? FOR UPDATE",(rs,n)->{var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("client",rs.getString("client_id"));row.put("product",rs.getString("product_id"));row.put("warehouse",rs.getString("warehouse_id"));row.put("location",rs.getString("location_id"));row.put("onhand",rs.getLong("on_hand"));row.put("batch",rs.getString("batch_number"));row.put("serial",rs.getString("serial_number"));row.put("expiry",rs.getString("expiry_date"));return row;},id);
        if(rows.isEmpty())throw WarehouseException.conflict("Inventory history cannot be deleted");var row=rows.get(0);
        var layers=jdbc.query("SELECT id,origin_id,received_at,remaining_quantity FROM wh_stock_layer WHERE inventory_id=? AND remaining_quantity>0 ORDER BY id FOR UPDATE",(rs,n)->Map.<String,Object>of("id",rs.getString(1),"originId",rs.getString(2),"receivedAt",rs.getTimestamp(3).toInstant().toString(),"quantity",rs.getLong(4)),id);
        if(layers.stream().mapToLong(l->((Number)l.get("quantity")).longValue()).sum()!=((Number)row.get("onhand")).longValue())throw WarehouseException.conflict("Storage source quantities do not reconcile / 仓储历史来源数量不一致");row.put("layers",layers);return row;
    }
    static Map<String,Object> decoded(Map<String,Object> row){var value=WarehouseJson.map(String.valueOf(row.get("payload_json")));if(!FinanceInput.hash(value).equals(row.get("payload_hash")))throw WarehouseException.conflict("Historical storage evidence checksum failed / 仓储历史校验失败");return value;}
}
