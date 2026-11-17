package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Called only under OrderService's order lock and transaction. */
final class OrderWork {
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final JdbcTemplate jdbc;
    OrderWork(WarehouseRepository records,WarehouseScope scope){this.records=records;this.scope=scope;this.jdbc=records.jdbc();}
    boolean required(Map<String,Object> order,Map<String,Object> client){return WarehouseJson.enabled(order.get("scanRequired"))||WarehouseJson.enabled(client.get("requireScan"));}

    Map<String,Object> view(String order,List<Map<String,Object>> allocations,boolean lock){
        var scans=scans(order,lock);var packages=packages(order,lock);var scanned=scanned(scans);var packed=packed(packages);var progress=new ArrayList<Map<String,Object>>();
        for(var allocation:allocations){var row=WarehouseJson.copy(allocation);String id=text(row,"id");long quantity=number(row,"quantity");row.put("scanned",scanned.getOrDefault(id,0L));row.put("packed",packed.getOrDefault(id,0L));row.put("scanRemaining",Math.max(0,quantity-scanned.getOrDefault(id,0L)));row.put("packRemaining",Math.max(0,quantity-packed.getOrDefault(id,0L)));progress.add(row);}
        var out=new LinkedHashMap<String,Object>();out.put("progress",progress);out.put("scans",scans.subList(Math.max(0,scans.size()-100),scans.size()));out.put("scanEventCount",scans.size());out.put("packages",packages);out.put("scanningComplete",complete(allocations,scanned));out.put("packingComplete",complete(allocations,packed));out.put("activePackageCount",packages.stream().filter(p->!"VOID".equals(p.get("state"))).count());out.put("totalWeightKg",packages.stream().filter(p->!"VOID".equals(p.get("state"))).map(p->new BigDecimal(text(p,"weightKg"))).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());return out;
    }
    void scan(Map<String,Object> order,List<Map<String,Object>> allocations,Map<String,Object> input){
        String id=text(order,"id");var allocation=reserved(allocations,text(input,"allocationId"));var history=scans(id,true);
        if(history.size()>=20000)throw WarehouseException.conflict("Order scan history limit reached / 本单扫码记录已达上限");
        long count=scanned(history).getOrDefault(text(allocation,"id"),0L);if(count>=number(allocation,"quantity"))throw WarehouseException.conflict("Allocated quantity is already scanned / 该库存分配行已经扫齐");
        String barcode=text(input,"barcode"),product=text(allocation,"product");
        var matches=records.clientRecordsCurrent("products",text(order,"client")).stream().filter(p->matches(p,barcode)).toList();
        if(matches.size()!=1||!product.equals(matches.get(0).get("id")))throw WarehouseException.conflict(matches.size()>1?"Barcode matches multiple products; correct master data first / 条码对应多个商品，请先修正商品资料":"Scanned code does not match this allocated product / 扫描条码与所选商品不一致");
        if(!text(allocation,"serial").equals(text(input,"serial")))throw WarehouseException.conflict("Serial does not match the reserved unit / 串号与已分配实物不一致");
        String scan=UUID.randomUUID().toString();jdbc.update("INSERT INTO wh_order_scan(id,order_id,allocation_id,barcode,serial_number,state,actor_id) VALUES(?,?,?,?,?,'ACTIVE',?)",scan,id,allocation.get("id"),barcode,input.get("serial"),scope.actor());
        records.audit("ORDER_SCAN","orders",id,null,Map.of("scanId",scan,"allocationId",allocation.get("id"),"barcode",barcode,"serial",input.get("serial")));
    }
    void unscan(Map<String,Object> order,Map<String,Object> input){
        String id=text(order,"id");var history=scans(id,true);var scan=history.stream().filter(s->input.get("scanId").equals(s.get("id"))).findFirst().orElseThrow(WarehouseException::missing);
        if(!"ACTIVE".equals(scan.get("state")))throw WarehouseException.conflict("Scan is already void / 扫码记录已撤销");
        String allocation=text(scan,"allocationId");if(scanned(history).getOrDefault(allocation,0L)-1<packed(packages(id,true)).getOrDefault(allocation,0L))throw WarehouseException.conflict("Unpack the affected goods before undoing a scan / 请先撤销对应装箱，再撤销扫码");
        if(jdbc.update("UPDATE wh_order_scan SET state='VOID',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason=? WHERE id=? AND state='ACTIVE'",scope.actor(),input.get("reason"),scan.get("id"))!=1)throw WarehouseException.conflict("Scan changed");
        records.audit("ORDER_UNSCAN","orders",id,scan,Map.of("reason",input.get("reason")));
    }
    void assertScanned(String order,List<Map<String,Object>> allocations){if(!complete(allocations,scanned(scans(order,true))))throw WarehouseException.conflict("Scan every allocated unit before confirming picking / 请先扫齐所有已分配商品");}
    void pack(Map<String,Object> order,List<Map<String,Object>> allocations,Map<String,Object> input){
        String id=text(order,"id");assertScanned(id,allocations);var packages=packages(id,true);if(packages.size()>=200)throw WarehouseException.conflict("Order package history limit reached / 本单包裹记录已达上限");var packed=packed(packages);
        for(var line:WarehouseJson.maps(input.get("lines"))){var allocation=reserved(allocations,text(line,"allocationId"));if(packed.getOrDefault(text(allocation,"id"),0L)+number(line,"quantity")>number(allocation,"quantity"))throw WarehouseException.conflict("Package quantity exceeds unpacked allocation / 装箱数量超过剩余可装数量");}
        String packageId=UUID.randomUUID().toString();jdbc.update("INSERT INTO wh_order_package(id,order_id,package_code,state,weight_kg,length_cm,width_cm,height_cm,notes,actor_id) VALUES(?,?,?,'SEALED',?,?,?,?,?,?)",packageId,id,input.get("packageCode"),input.get("weightKg"),input.get("lengthCm"),input.get("widthCm"),input.get("heightCm"),input.get("notes"),scope.actor());
        for(var line:WarehouseJson.maps(input.get("lines")))jdbc.update("INSERT INTO wh_order_package_line(package_id,allocation_id,quantity) VALUES(?,?,?)",packageId,line.get("allocationId"),line.get("quantity"));
        records.audit("ORDER_PACK","orders",id,null,Map.of("packageId",packageId,"manifest",input));
    }
    void unpack(Map<String,Object> order,Map<String,Object> input){String id=text(order,"id");var pack=packages(id,true).stream().filter(p->input.get("packageId").equals(p.get("id"))).findFirst().orElseThrow(WarehouseException::missing);if(!"SEALED".equals(pack.get("state")))throw WarehouseException.conflict("Only sealed, undispatched packages can be voided / 只能撤销尚未出库的封箱记录");jdbc.update("UPDATE wh_order_package SET state='VOID',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason=? WHERE id=? AND state='SEALED'",scope.actor(),input.get("reason"),pack.get("id"));records.audit("ORDER_UNPACK","orders",id,pack,Map.of("reason",input.get("reason")));}
    void requireNoPackages(String id){if(packages(id,true).stream().anyMatch(p->!"VOID".equals(p.get("state"))))throw WarehouseException.conflict("Unpack before reopening picking / 请先撤销装箱，再返回拣货核验");}
    void despatch(Map<String,Object> order,Map<String,Object> client,List<Map<String,Object>> allocations){
        String id=text(order,"id");var packages=packages(id,true);var active=packages.stream().filter(p->!"VOID".equals(p.get("state"))).toList();
        if(required(order,client)||!active.isEmpty()){assertScanned(id,allocations);if(active.isEmpty()||active.stream().anyMatch(p->!"SEALED".equals(p.get("state")))||!complete(allocations,packed(packages)))throw WarehouseException.conflict("Seal packages covering every allocated unit before despatch / 所有商品装箱完成后才能出库");
            order.put("packageCount",active.size());order.put("confirmedPackingWeight",active.stream().map(p->new BigDecimal(text(p,"weightKg"))).reduce(BigDecimal.ZERO,BigDecimal::add).toPlainString());
            if(jdbc.update("UPDATE wh_order_package SET state='DESPATCHED' WHERE order_id=? AND state='SEALED'",id)!=active.size())throw WarehouseException.conflict("Package manifest changed");
        }
    }
    void cancel(String id){jdbc.update("UPDATE wh_order_scan SET state='VOID',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason='Order cancelled' WHERE order_id=? AND state='ACTIVE'",scope.actor(),id);jdbc.update("UPDATE wh_order_package SET state='VOID',voided_by=?,voided_at=CURRENT_TIMESTAMP(6),void_reason='Order cancelled' WHERE order_id=? AND state='SEALED'",scope.actor(),id);}
    private List<Map<String,Object>> scans(String id,boolean lock){return jdbc.query("SELECT * FROM wh_order_scan WHERE order_id=? ORDER BY created_at,id"+(lock?" FOR UPDATE":""),(rs,n)->{var row=new LinkedHashMap<String,Object>();for(String[] p:List.of(new String[]{"id","id"},new String[]{"allocation_id","allocationId"},new String[]{"barcode","barcode"},new String[]{"serial_number","serial"},new String[]{"state","state"},new String[]{"void_reason","voidReason"}))row.put(p[1],rs.getString(p[0]));row.put("actorId",rs.getLong("actor_id"));row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());row.put("voidedAt",timestamp(rs,"voided_at"));return row;},id);}
    private List<Map<String,Object>> packages(String id,boolean lock){List<Map<String,Object>> rows=jdbc.query("SELECT * FROM wh_order_package WHERE order_id=? ORDER BY created_at,id"+(lock?" FOR UPDATE":""),(rs,n)->{var row=new LinkedHashMap<String,Object>();for(String[] p:List.of(new String[]{"id","id"},new String[]{"package_code","packageCode"},new String[]{"state","state"},new String[]{"notes","notes"},new String[]{"void_reason","voidReason"}))row.put(p[1],rs.getString(p[0]));for(String[] p:List.of(new String[]{"weight_kg","weightKg"},new String[]{"length_cm","lengthCm"},new String[]{"width_cm","widthCm"},new String[]{"height_cm","heightCm"}))row.put(p[1],rs.getBigDecimal(p[0]).toPlainString());row.put("actorId",rs.getLong("actor_id"));row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());row.put("voidedAt",timestamp(rs,"voided_at"));return row;},id);for(var row:rows)row.put("lines",jdbc.query("SELECT allocation_id,quantity FROM wh_order_package_line WHERE package_id=? ORDER BY allocation_id"+(lock?" FOR UPDATE":""),(rs,n)->Map.<String,Object>of("allocationId",rs.getString(1),"quantity",rs.getLong(2)),row.get("id")));return rows;}
    private static boolean matches(Map<String,Object> product,String code){String barcode=text(product,"barcode"),upc=text(product,"upc");return !barcode.isEmpty()&&barcode.equals(code)||!upc.isEmpty()&&upc.equals(code)||barcode.isEmpty()&&upc.isEmpty()&&text(product,"sku").equals(code);}
    private static Map<String,Long> scanned(List<Map<String,Object>> scans){var out=new HashMap<String,Long>();for(var row:scans)if("ACTIVE".equals(row.get("state")))out.merge(text(row,"allocationId"),1L,Math::addExact);return out;}
    private static Map<String,Long> packed(List<Map<String,Object>> packages){var out=new HashMap<String,Long>();for(var p:packages)if(!"VOID".equals(p.get("state")))for(var row:WarehouseJson.maps(p.get("lines")))out.merge(text(row,"allocationId"),number(row,"quantity"),Math::addExact);return out;}
    private static boolean complete(List<Map<String,Object>> allocations,Map<String,Long> actual){if(allocations.isEmpty())return false;var expected=new HashMap<String,Long>();for(var row:allocations)if(!"RELEASED".equals(row.get("state")))expected.put(text(row,"id"),number(row,"quantity"));return !expected.isEmpty()&&expected.equals(actual);}
    private static Map<String,Object> reserved(List<Map<String,Object>> allocations,String id){return allocations.stream().filter(a->id.equals(a.get("id"))&&"RESERVED".equals(a.get("state"))).findFirst().orElseThrow(()->WarehouseException.conflict("Allocation is not reserved for this order / 库存不属于此订单的有效分配"));}
    private static String text(Map<String,Object> row,String key){return WarehouseJson.text(row,key);}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Object timestamp(ResultSet rs,String key)throws SQLException{var value=rs.getTimestamp(key);return value==null?null:value.toInstant().toString();}
}
