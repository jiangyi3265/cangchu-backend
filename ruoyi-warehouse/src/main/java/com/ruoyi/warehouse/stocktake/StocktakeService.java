package com.ruoyi.warehouse.stocktake;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.inventory.*;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class StocktakeService {
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;
    public StocktakeService(WarehouseRepository records, WarehouseScope scope, InventoryService inventory) {
        this.records=records;this.scope=scope;this.inventory=inventory;this.jdbc=records.jdbc();
    }
    public Map<String,Object> list(int page,int size,String keyword,String client,String warehouse) {
        if(client.length()>64||warehouse.length()>64)throw WarehouseException.invalid("Invalid stocktake filter");
        return records.list("stocktakes",page,size,keyword,Map.of("client",client,"warehouse",warehouse));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(String id) { return view(records.scoped("stocktakes",id,false)); }

    @Transactional
    public Map<String,Object> create(Map<String,Object> input) {
        StocktakeInput.keys(input,"requestId","client","warehouse","reference","notes","locations");
        String client=StocktakeInput.id(input,"client"),warehouse=StocktakeInput.id(input,"warehouse"),request=StocktakeInput.id(input,"requestId");
        var data=new LinkedHashMap<String,Object>();data.put("client",client);data.put("warehouse",warehouse);
        data.put("reference",StocktakeInput.text(input,"reference",120,true));data.put("notes",StocktakeInput.text(input,"notes",2000,false));
        if(!(input.get("locations") instanceof List<?> raw)||raw.isEmpty()||raw.size()>100)throw WarehouseException.invalid("Select 1–100 locations / 请选择 1 至 100 个库位");
        var locations=new TreeSet<String>();for(Object value:raw){String location=StocktakeInput.id(Map.of("location",value==null?"":value),"location");if(!locations.add(location))throw WarehouseException.invalid("Duplicate location");}
        data.put("locations",new ArrayList<>(locations));checkScope(data);
        String hash=StocktakeInput.hash(data);var replay=claim(request,"STOCKTAKE_CREATE",hash);
        if(replay!=null){records.scoped("stocktakes",text(replay,"id"),false);return replay;}
        lockContext(data);
        String id=UUID.randomUUID().toString();data.put("workflow","JAVA_STOCKTAKE_V1");data.put("status","COUNTING");data.put("createdBy",scope.actor());data.put("counters",List.of());
        var saved=records.write("stocktakes",id,data,null,text(data,"reference").toLowerCase(Locale.ROOT),client);
        var refs=new LinkedHashMap<String,String>();refs.put("client",client);refs.put("warehouse",warehouse);int index=0;
        for(String location:locations){
            StocktakeLock.available(jdbc,client,warehouse,location);
            jdbc.update("INSERT INTO wh_stocktake_lock(client_id,warehouse_id,location_id,stocktake_id) VALUES(?,?,?,?)",client,warehouse,location,id);refs.put("location"+index++,location);
            var pools=jdbc.queryForList("SELECT * FROM wh_inventory WHERE client_id=? AND warehouse_id=? AND location_id=? AND (on_hand>0 OR reserved>0) ORDER BY id FOR UPDATE",client,warehouse,location);
            for(var pool:pools){line(id,text(pool,"product_id"),location,text(pool,"batch_number"),text(pool,"serial_number"),text(pool,"expiry_date"),pool,false);}
        }
        if(lines(id,true).size()>2000)throw WarehouseException.invalid("Count at most 2000 stock pools per document; split the locations / 每单最多 2000 行库存，请拆分库位");
        records.replaceReferences(id,refs);records.audit("STOCKTAKE_START","stocktakes",id,null,view(saved));
        return complete(request,hash,view(saved));
    }

    @Transactional
    public Map<String,Object> action(String id,Map<String,Object> input) {
        String action=StocktakeInput.text(input,"action",16,true),request=StocktakeInput.id(input,"requestId");
        switch(action){
            case "count" -> StocktakeInput.keys(input,"requestId","_version","action","lines");
            case "found" -> StocktakeInput.keys(input,"requestId","_version","action","product","location","batch","serial","expiry","counted","reason");
            case "approve" -> StocktakeInput.keys(input,"requestId","_version","action","reason","selfApproval");
            case "submit" -> StocktakeInput.keys(input,"requestId","_version","action");
            case "cancel","reopen" -> StocktakeInput.keys(input,"requestId","_version","action","reason");
            default -> throw WarehouseException.invalid("Unsupported stocktake action");
        }
        long version=WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE);records.scoped("stocktakes",id,false);
        var canonical=new LinkedHashMap<>(input);canonical.remove("requestId");canonical.put("id",id);String hash=StocktakeInput.hash(canonical);
        var replay=claim(request,"STOCKTAKE_ACTION",hash);if(replay!=null)return replay;
        var before=records.scoped("stocktakes",id,true);
        if(!"JAVA_STOCKTAKE_V1".equals(before.get("workflow"))||number(before,"_version")!=version)throw WarehouseException.conflict("Count changed; refresh first / 盘点已更新，请刷新后继续");
        var data=WarehouseJson.copy(before);for(String key:List.of("id","_version","createdAt","updatedAt"))data.remove(key);
        String state=text(data,"status");if(!Set.of("COUNTING","REVIEW").contains(state))throw WarehouseException.conflict("Stocktake is closed / 盘点已结束");
        var rows=lines(id,true);
        switch(action){
            case "count" -> { require(state,"COUNTING");count(id,rows,input);participate(data); }
            case "found" -> { require(state,"COUNTING");found(id,data,rows,input);participate(data); }
            case "submit" -> {
                require(state,"COUNTING");requireCounts(rows);data.put("status","REVIEW");data.put("submittedBy",scope.actor());data.put("submittedAt",Instant.now().toString());
            }
            case "reopen" -> {
                require(state,"REVIEW");String reason=StocktakeInput.text(input,"reason",500,true);
                records.audit("STOCKTAKE_RECOUNT_EVIDENCE","stocktakes",id,rows,Map.of("reason",reason));
                jdbc.update("UPDATE wh_stocktake_line SET counted_quantity=NULL,count_reason='',counted_by=NULL,counted_at=NULL WHERE stocktake_id=?",id);
                data.put("status","COUNTING");data.put("recountReason",reason);
            }
            case "approve" -> {
                require(state,"REVIEW");requireCounts(rows);String reason=StocktakeInput.text(input,"reason",500,true);
                Object override=input.getOrDefault("selfApproval",false);if(!(override instanceof Boolean))throw WarehouseException.invalid("selfApproval must be boolean");
                boolean participated=number(data,"createdBy")==scope.actor()||counters(data).contains(scope.actor());
                if(Boolean.TRUE.equals(override)&&!scope.admin()||participated&&!(scope.admin()&&Boolean.TRUE.equals(override)))throw WarehouseException.forbidden();
                for(String product:rows.stream().map(row->text(row,"product")).distinct().sorted().toList())records.scoped("products",product,true);
                lockContext(data);assertFreeze(id,data);
                var ordered=new ArrayList<>(rows);ordered.sort(Comparator.comparingLong(row->number(row,"counted")-number(row,"expected")));
                for(var row:ordered){var result=inventory.reconcileStocktake(id,text(row,"id"));jdbc.update("UPDATE wh_stocktake_line SET result_json=? WHERE id=?",WarehouseJson.write(result),row.get("id"));}
                data.put("status","APPROVED");data.put("approvedBy",scope.actor());data.put("approvedAt",Instant.now().toString());data.put("approvalReason",reason);data.put("selfApproved",participated);release(id,data);
            }
            case "cancel" -> {
                String reason=StocktakeInput.text(input,"reason",500,true);checkScope(data);
                // Cancellation must still unfreeze stock if master policies changed.
                records.get("clients",text(data,"client"),true);records.get("warehouses",text(data,"warehouse"),true);
                for(String location:locations(data))records.get("locations",location,true);
                assertFreeze(id,data);release(id,data);data.put("status","CANCELLED");data.put("cancelReason",reason);data.put("cancelledBy",scope.actor());
            }
            default -> throw WarehouseException.invalid("Unsupported action");
        }
        var saved=records.write("stocktakes",id,data,version,text(data,"reference").toLowerCase(Locale.ROOT),text(data,"client"));
        records.audit("STOCKTAKE_"+action.toUpperCase(Locale.ROOT),"stocktakes",id,before,saved);
        return complete(request,hash,view(saved));
    }

    private void count(String id,List<Map<String,Object>> rows,Map<String,Object> input) {
        if(!(input.get("lines") instanceof List<?> values)||values.isEmpty()||values.size()>200)throw WarehouseException.invalid("Save 1–200 count lines at once / 每次保存 1 至 200 行");
        var seen=new HashSet<String>();
        for(Object value:values){
            if(!(value instanceof Map<?,?>))throw WarehouseException.invalid("Invalid count line");var update=WarehouseJson.map(WarehouseJson.write(value));StocktakeInput.keys(update,"id","counted","reason");
            String lineId=StocktakeInput.id(update,"id");if(!seen.add(lineId))throw WarehouseException.invalid("Duplicate count line");
            var row=rows.stream().filter(r->lineId.equals(r.get("id"))).findFirst().orElseThrow(WarehouseException::missing);
            long counted=StocktakeInput.quantity(update.get("counted"));String reason=StocktakeInput.text(update,"reason",500,counted!=number(row,"expected"));
            if(!text(row,"serial").isEmpty()&&counted>1)throw WarehouseException.invalid("Serial count must be 0 or 1 / 串号库存只能盘为 0 或 1 件");
            jdbc.update("UPDATE wh_stocktake_line SET counted_quantity=?,count_reason=?,counted_by=?,counted_at=CURRENT_TIMESTAMP(6) WHERE id=? AND stocktake_id=?",counted,reason,scope.actor(),lineId,id);
            records.audit("STOCKTAKE_COUNT_LINE","stocktakes",id,row,Map.of("id",lineId,"counted",counted,"reason",reason));
        }
    }
    private void found(String id,Map<String,Object> head,List<Map<String,Object>> rows,Map<String,Object> input) {
        if(rows.size()>=2000)throw WarehouseException.conflict("Stocktake line limit reached");
        String product=StocktakeInput.id(input,"product"),location=StocktakeInput.id(input,"location");
        if(!locations(head).contains(location))throw WarehouseException.forbidden();
        var p=records.scoped("products",product,true);if(!head.get("client").equals(p.get("client")))throw WarehouseException.forbidden();
        if(WarehouseJson.enabled(p.get("bundle"))||WarehouseJson.enabled(p.get("dropship"))||WarehouseJson.enabled(p.get("infiniteStock")))throw WarehouseException.invalid("Only physical products can be counted");
        lockContext(head);assertFreeze(id,head);
        String batch=StocktakeInput.text(input,"batch",191,false),serial=StocktakeInput.text(input,"serial",191,false),expiry=StocktakeInput.text(input,"expiry",10,false),reason=StocktakeInput.text(input,"reason",500,true);
        long counted=StocktakeInput.quantity(input.get("counted"));if(counted==0)throw WarehouseException.invalid("Found stock quantity must be positive");
        var check=InventoryMovement.parse(Map.of("requestId",UUID.randomUUID().toString(),"action","add","product",product,"warehouse",head.get("warehouse"),"location",location,"quantity",1,"reason",reason,"batch",batch,"serial",serial,"expiry",expiry));check.validateTracking(p);
        if(!serial.isEmpty()&&counted!=1)throw WarehouseException.invalid("Serial count must be one");
        var pools=jdbc.queryForList("SELECT * FROM wh_inventory WHERE product_id=? AND warehouse_id=? AND location_id=? AND batch_number=? AND serial_number=? AND expiry_date=? FOR UPDATE",product,head.get("warehouse"),location,batch,serial,expiry);
        if(!pools.isEmpty()&&number(pools.get(0),"on_hand")>0)throw WarehouseException.conflict("Use the existing count line / 请修改已有盘点行");
        String lineId=line(id,product,location,batch,serial,expiry,pools.isEmpty()?null:pools.get(0),true);
        jdbc.update("UPDATE wh_stocktake_line SET counted_quantity=?,count_reason=?,counted_by=?,counted_at=CURRENT_TIMESTAMP(6) WHERE id=?",counted,reason,scope.actor(),lineId);
        records.audit("STOCKTAKE_FOUND_LINE","stocktakes",id,null,Map.of("lineId",lineId,"counted",counted,"reason",reason,"product",product,"location",location));
    }
    private String line(String owner,String product,String location,String batch,String serial,String expiry,Map<String,Object> pool,boolean unexpected) {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wh_stocktake_line(id,stocktake_id,product_id,location_id,inventory_id,batch_number,serial_number,expiry_date,expected_quantity,expected_reserved,inventory_revision,unexpected) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,owner,product,location,pool==null?null:pool.get("id"),batch,serial,expiry,pool==null?0:pool.get("on_hand"),pool==null?0:pool.get("reserved"),pool==null?null:pool.get("revision"),unexpected);
        return id;
    }
    private void lockContext(Map<String,Object> head) {
        checkScope(head);var client=records.get("clients",text(head,"client"),true);records.get("warehouses",text(head,"warehouse"),true);
        if(WarehouseJson.enabled(client.get("infiniteStock")))throw WarehouseException.invalid("Infinite stock cannot be physically counted");
        for(String id:locations(head)){
            var loc=records.get("locations",id,true);String owner=text(loc,"client");
            if(!head.get("warehouse").equals(loc.get("warehouse"))||!owner.isEmpty()&&!owner.equals(head.get("client")))throw WarehouseException.forbidden();
            if(WarehouseJson.enabled(loc.get("custodyOnly"))||"Quarantine".equals(loc.get("type")))throw WarehouseException.conflict("Custody/quarantine counting requires its dedicated stock workflow");
        }
    }
    private void assertFreeze(String id,Map<String,Object> head) {
        for(String location:locations(head))if(!id.equals(StocktakeLock.owner(jdbc,text(head,"client"),text(head,"warehouse"),location)))throw WarehouseException.conflict("Stocktake freeze is missing");
    }
    private void release(String id,Map<String,Object> head) {
        if(jdbc.update("DELETE FROM wh_stocktake_lock WHERE stocktake_id=?",id)!=locations(head).size())throw WarehouseException.conflict("Stocktake freeze changed");
    }
    private List<Map<String,Object>> lines(String id,boolean lock) {
        return jdbc.query("SELECT * FROM wh_stocktake_line WHERE stocktake_id=? ORDER BY location_id,product_id,id"+(lock?" FOR UPDATE":""),(rs,n)->{
            var row=new LinkedHashMap<String,Object>();
            for(String[] f:List.of(new String[]{"id","id"},new String[]{"product_id","product"},new String[]{"location_id","location"},new String[]{"inventory_id","inventoryId"},new String[]{"batch_number","batch"},new String[]{"serial_number","serial"},new String[]{"expiry_date","expiry"},new String[]{"count_reason","reason"}))row.put(f[1],rs.getString(f[0]));
            row.put("expected",rs.getLong("expected_quantity"));row.put("reserved",rs.getLong("expected_reserved"));row.put("counted",rs.getObject("counted_quantity"));row.put("countedBy",rs.getObject("counted_by"));row.put("unexpected",rs.getBoolean("unexpected"));
            var at=rs.getTimestamp("counted_at");row.put("countedAt",at==null?null:at.toInstant().toString());String result=rs.getString("result_json");if(result!=null)row.put("result",WarehouseJson.map(result));return row;
        },id);
    }
    private Map<String,Object> view(Map<String,Object> head) {
        var out=WarehouseJson.copy(head);var rows=lines(text(head,"id"),TransactionSynchronizationManager.isActualTransactionActive()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        var products=new HashMap<String,Map<String,Object>>();var places=new HashMap<String,Map<String,Object>>();
        for(var row:rows){var p=products.computeIfAbsent(text(row,"product"),id->records.get("products",id,false));row.put("sku",p.get("sku"));row.put("productName",p.get("name"));var loc=places.computeIfAbsent(text(row,"location"),id->records.get("locations",id,false));row.put("locationName",loc.get("name"));if(row.get("counted")!=null)row.put("difference",number(row,"counted")-number(row,"expected"));}
        out.put("lines",rows);out.put("uncounted",rows.stream().filter(row->row.get("counted")==null).count());out.put("differenceLines",rows.stream().filter(row->row.get("difference")!=null&&number(row,"difference")!=0).count());out.put("selfApprovalRequired",number(head,"createdBy")==scope.actor()||counters(head).contains(scope.actor()));return out;
    }
    private void requireCounts(List<Map<String,Object>> rows) {
        if(rows.stream().anyMatch(row->row.get("counted")==null))throw WarehouseException.conflict("Every line needs an explicit physical count, including zero / 请逐行填写实盘数量，零库存也需填写 0");
        if(rows.stream().anyMatch(row->number(row,"counted")!=number(row,"expected")&&text(row,"reason").isBlank()))throw WarehouseException.conflict("Every difference needs a reason");
    }
    private void participate(Map<String,Object> head) { var people=counters(head);people.add(scope.actor());head.put("counters",new ArrayList<>(people)); }
    private static SortedSet<Long> counters(Map<String,Object> head) {var out=new TreeSet<Long>();if(head.get("counters") instanceof List<?> values)for(Object value:values)out.add(((Number)value).longValue());return out;}
    @SuppressWarnings("unchecked") private static List<String> locations(Map<String,Object> head) { return (List<String>)head.get("locations"); }
    private void checkScope(Map<String,Object> head) { if(!scope.granted("CLIENT",text(head,"client"))||!scope.granted("WAREHOUSE",text(head,"warehouse")))throw WarehouseException.forbidden(); }
    private Map<String,Object> claim(String request,String type,String hash) {
        jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,type,hash,"{}");
        return jdbc.queryForObject("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE",(rs,n)->{if(!type.equals(rs.getString(1))||!hash.equals(rs.getString(2)))throw WarehouseException.conflict("requestId already used for different content");var result=WarehouseJson.map(rs.getString(3));return result.isEmpty()?null:result;},scope.actor(),request);
    }
    private Map<String,Object> complete(String request,String hash,Map<String,Object> result) {if(jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?",WarehouseJson.write(result),scope.actor(),request,hash)!=1)throw WarehouseException.conflict("Stocktake journal changed");return result;}
    private static void require(String actual,String expected) { if(!expected.equals(actual))throw WarehouseException.conflict("Stocktake action requires "+expected+" / 当前盘点状态不允许此操作"); }
    private static String text(Map<String,Object> row,String key) {return WarehouseJson.text(row,key);}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
}
