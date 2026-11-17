package com.ruoyi.warehouse.returns;

import com.ruoyi.warehouse.billing.BillingCalculator;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.inventory.InventoryService;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One product per RMA, with explicit consumed-allocation provenance and physical custody. */
@Service
public class ReturnService {
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;
    public ReturnService(WarehouseRepository records,WarehouseScope scope,InventoryService inventory){this.records=records;this.scope=scope;this.inventory=inventory;jdbc=records.jdbc();}
    @Transactional(readOnly=true)
    public Map<String,Object> list(int page,int size,String keyword,String client,String warehouse){return records.list("returns",page,size,keyword,Map.of("client",client,"warehouse",warehouse));}
    @Transactional(readOnly=true)
    public Map<String,Object> get(String id){return view(records.scoped("returns",id,false));}

    @Transactional
    public Map<String,Object> create(Map<String,Object> input) {
        ReturnInput.keys(input,"requestId","order","product","quantity","reference","reason");
        String orderId=ReturnInput.id(input,"order"),productId=ReturnInput.id(input,"product"),request=ReturnInput.id(input,"requestId"),hash=ReturnInput.hash(input);
        String reference=ReturnInput.text(input,"reference",120,true),reason=ReturnInput.text(input,"reason",500,true);long quantity=ReturnInput.quantity(input.get("quantity"));
        records.scoped("orders",orderId,false);records.scoped("products",productId,false);
        var replay=claim(request,"RETURN_CREATE",hash);if(replay!=null){records.scoped("returns",text(replay,"id"),false);return replay;}
        var order=records.scoped("orders",orderId,true);shipped(order);
        var product=records.scoped("products",productId,true);if(!order.get("client").equals(product.get("client")))throw WarehouseException.forbidden();
        var client=records.scoped("clients",text(order,"client"),true);records.scoped("warehouses",text(order,"warehouse"),true);lockLocations(text(order,"warehouse"));
        long shipped=WarehouseJson.maps(order.get("lines")).stream().filter(l->productId.equals(l.get("product"))).mapToLong(l->((Number)l.get("quantity")).longValue()).sum();
        long consumed=sources(orderId,productId,true).stream().mapToLong(l->((Number)l.get("quantity")).longValue()).sum();
        if(shipped==0||consumed!=shipped)throw WarehouseException.conflict("Complete despatch source evidence is required / 缺少完整原发货来源，不能猜测退货批次");
        long promised=0;
        for(String sibling:jdbc.queryForList("SELECT r.id FROM wh_record r JOIN wh_reference ref ON ref.owner_id=r.id WHERE r.kind='returns' AND ref.field_name='order' AND ref.target_id=? ORDER BY r.id FOR UPDATE",String.class,orderId)){
            var row=records.get("returns",sibling,true);if(!productId.equals(row.get("product"))||"CANCELLED".equals(row.get("status")))continue;
            promised=Math.addExact(promised,number(row,"CLOSED".equals(row.get("status"))?"received":"quantity"));
        }
        if(promised+quantity>shipped)throw WarehouseException.conflict("Return quantity exceeds unclaimed despatched quantity / 退货数量超过原发货量（含其他未取消退货单）");
        String currency=text(client,"currency");if(!Set.of("GBP","USD","EUR").contains(currency))throw WarehouseException.conflict("Unsupported return currency");
        var rates=new LinkedHashMap<String,Object>();for(String key:List.of("returnFirst","returnAdditional")){boolean override=!WarehouseJson.empty(product.get(key));rates.put(key,ReturnInput.rate(override?product.get(key):client.get(key)));rates.put(key+"Source",(override?"product:":"client:")+(override?productId:text(order,"client")));}
        var data=new LinkedHashMap<String,Object>();data.put("workflow","JAVA_RETURN_V1");data.put("order",orderId);data.put("orderReference",order.get("reference"));data.put("product",productId);data.put("sku",product.get("sku"));data.put("client",order.get("client"));data.put("warehouse",order.get("warehouse"));data.put("quantity",quantity);data.put("received",0);data.put("restocked",0);data.put("scrapped",0);data.put("status","NEW");data.put("reference",reference);data.put("reason",reason);data.put("currency",currency);data.put("rates",rates);
        String id=UUID.randomUUID().toString();var saved=records.write("returns",id,data,null,reference.toLowerCase(Locale.ROOT),text(data,"client"));
        records.replaceReferences(id,Map.of("client",text(data,"client"),"warehouse",text(data,"warehouse"),"order",orderId,"product",productId));records.audit("RETURN_CREATE","returns",id,null,saved);
        return complete(request,hash,view(saved));
    }

    @Transactional
    public Map<String,Object> action(String id,Map<String,Object> input) {
        String action=ReturnInput.text(input,"action",20,true);
        switch(action){
            case "receive" -> ReturnInput.keys(input,"requestId","action","_version","allocation","location","quantity");
            case "inspect" -> ReturnInput.keys(input,"requestId","action","_version","receipt","result","reason");
            case "restock" -> ReturnInput.keys(input,"requestId","action","_version","receipt","quantity","location","reason");
            case "scrap" -> ReturnInput.keys(input,"requestId","action","_version","receipt","quantity","reason");
            case "close" -> ReturnInput.keys(input,"requestId","action","_version","reason","approveShortfall");
            case "cancel" -> ReturnInput.keys(input,"requestId","action","_version","reason");
            default -> throw WarehouseException.invalid("Unsupported return action");
        }
        long version=ReturnInput.version(input);String request=ReturnInput.id(input,"requestId"),hash=ReturnInput.hash(Map.of("id",id,"input",input));
        var visible=records.scoped("returns",id,false);records.scoped("orders",text(visible,"order"),false);
        var replay=claim(request,"RETURN_"+action.toUpperCase(Locale.ROOT),hash);if(replay!=null)return replay;
        var order=records.scoped("orders",text(visible,"order"),true);shipped(order);var before=records.scoped("returns",id,true);
        if(!"JAVA_RETURN_V1".equals(before.get("workflow"))||!order.get("id").equals(before.get("order"))||!order.get("client").equals(before.get("client"))||!order.get("warehouse").equals(before.get("warehouse")))throw WarehouseException.conflict("Invalid return source context");
        if(number(before,"_version")!=version)throw WarehouseException.conflict("Return changed; refresh before retrying / 退货单已更新，请刷新");
        if(Set.of("CANCELLED","CLOSED").contains(text(before,"status")))throw WarehouseException.conflict("Return is closed / 退货单已结束");
        var data=business(before);var product=records.scoped("products",text(before,"product"),true);if(!before.get("client").equals(product.get("client")))throw WarehouseException.forbidden();
        var client=records.scoped("clients",text(before,"client"),true);records.scoped("warehouses",text(before,"warehouse"),true);lockLocations(text(before,"warehouse"));
        String evidence="";
        if("receive".equals(action)) {
            long quantity=ReturnInput.quantity(input.get("quantity"));String allocation=ReturnInput.id(input,"allocation"),location=ReturnInput.id(input,"location");
            if(number(before,"received")+quantity>number(before,"quantity"))throw WarehouseException.conflict("Receipt exceeds this RMA / 实收超过退货单数量");
            var source=sources(text(before,"order"),text(before,"product"),true).stream().filter(s->allocation.equals(s.get("id"))).findFirst().orElseThrow(()->WarehouseException.conflict("Select an original consumed source / 请选择原发货来源"));
            if(number(source,"remaining")<quantity)throw WarehouseException.conflict("Original shipment has already been returned / 原发货来源可退数量不足");
            if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_return_receipt WHERE return_id=?",Long.class,id)>=250)throw WarehouseException.conflict("Return receipt limit reached");
            if(!Objects.equals(before.get("currency"),client.get("currency")))throw WarehouseException.conflict("Return currency changed; resolve pricing before receipt / 币种发生变化，请先核对");
            evidence=UUID.randomUUID().toString();jdbc.update("INSERT INTO wh_return_receipt(id,return_id,allocation_id,location_id,quantity,received_by) VALUES(?,?,?,?,?,?)",evidence,id,allocation,location,quantity,scope.actor());
            var stock=inventory.receiveReturn(id,evidence);
            jdbc.update("UPDATE wh_return_receipt SET layer_id=? WHERE id=?",stock.get("layerId"),evidence);
            charge(before,evidence,quantity);data.put("received",number(before,"received")+quantity);data.put("status",number(data,"received")==number(data,"quantity")?"RECEIVED":"PARTIALLY_RECEIVED");
        } else if(Set.of("inspect","restock","scrap").contains(action)) {
            String receipt=ReturnInput.id(input,"receipt"),reason=ReturnInput.text(input,"reason",500,true);var rows=jdbc.queryForList("SELECT * FROM wh_return_receipt WHERE id=? AND return_id=? FOR UPDATE",receipt,id);
            if(rows.size()!=1)throw WarehouseException.missing();var row=rows.get(0);long remaining=number(row,"quantity")-number(row,"disposed_quantity");
            if(remaining<=0)throw WarehouseException.conflict("Receipt has already been fully disposed / 本批退货已全部处置");
            if("inspect".equals(action)) {
                String result=ReturnInput.text(input,"result",16,true);if(!Set.of("PASS","FAIL").contains(result))throw WarehouseException.invalid("Inspection must be PASS or FAIL");
                if(number(row,"disposed_quantity")>0)throw WarehouseException.conflict("Cannot change inspection after partial disposition / 已部分处置，不能更改检验结论");
                jdbc.update("UPDATE wh_return_receipt SET inspection=?,inspection_reason=?,inspected_by=?,inspected_at=CURRENT_TIMESTAMP(6) WHERE id=?",result,reason,scope.actor(),receipt);evidence=receipt;
            }else{
                long quantity=ReturnInput.quantity(input.get("quantity"));if(quantity>remaining)throw WarehouseException.conflict("Disposition exceeds held quantity / 处置数量超过待处理数量");
                if(!("restock".equals(action)?"PASS":"FAIL").equals(row.get("inspection")))throw WarehouseException.conflict("Inspection does not permit this disposition / 请先完成对应的合格或不合格检验");
                String location="restock".equals(action)?ReturnInput.id(input,"location"):null;evidence=UUID.randomUUID().toString();
                jdbc.update("INSERT INTO wh_return_disposition(id,receipt_id,action_name,quantity,location_id,reason,actor_id) VALUES(?,?,?,?,?,?,?)",evidence,receipt,action,quantity,location,reason,scope.actor());
                var stock=inventory.disposeReturn(id,evidence);jdbc.update("UPDATE wh_return_disposition SET result_json=? WHERE id=?",WarehouseJson.write(stock),evidence);
                if(jdbc.update("UPDATE wh_return_receipt SET disposed_quantity=disposed_quantity+? WHERE id=? AND quantity-disposed_quantity>=?",quantity,receipt,quantity)!=1)throw WarehouseException.conflict("Return quantity changed");
                String key="restock".equals(action)?"restocked":"scrapped";data.put(key,number(before,key)+quantity);
            }
        }else{
            String reason=ReturnInput.text(input,"reason",500,true);data.put(action+"Reason",reason);
            if("cancel".equals(action)) {
                if(number(before,"received")!=0)throw WarehouseException.conflict("Received returns must be disposed and closed / 已收货不能取消，请处置后结案");data.put("status","CANCELLED");
            }else{
                if(number(before,"received")==0||number(before,"restocked")+number(before,"scrapped")!=number(before,"received"))throw WarehouseException.conflict("Receive and dispose all held goods before closure / 请先收货并处置全部隔离商品");
                if(input.containsKey("approveShortfall")&&!(input.get("approveShortfall") instanceof Boolean))throw WarehouseException.invalid("Shortfall approval must be boolean");
                if(number(before,"received")<number(before,"quantity")){scope.requireAdministrator();if(!Boolean.TRUE.equals(input.get("approveShortfall")))throw WarehouseException.conflict("Explicit shortfall approval is required / 短收结案需管理员明确确认");data.put("shortfallApprovedBy",scope.actor());}
                data.put("status","CLOSED");
            }
            data.put("closedAt",Instant.now().toString());
        }
        var saved=records.write("returns",id,data,version,text(before,"reference").toLowerCase(Locale.ROOT),text(before,"client"));records.audit("RETURN_"+action.toUpperCase(Locale.ROOT),"returns",id,before,Map.of("record",saved,"input",input,"evidenceId",evidence));
        return complete(request,hash,view(saved));
    }

    private List<Map<String,Object>> sources(String order,String product,boolean lock){
        var rows=jdbc.query("SELECT a.*,i.batch_number,i.serial_number,i.expiry_date,l.origin_id,l.received_at FROM wh_order_allocation a JOIN wh_inventory i ON i.id=a.inventory_id JOIN wh_stock_layer l ON l.id=a.layer_id WHERE a.order_id=? AND a.state='CONSUMED' AND i.product_id=? ORDER BY a.id"+(lock?" FOR UPDATE":""),(rs,n)->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",rs.getString("id"));row.put("quantity",rs.getLong("quantity"));row.put("batch",rs.getString("batch_number"));row.put("serial",rs.getString("serial_number"));row.put("expiry",rs.getString("expiry_date"));row.put("originId",rs.getString("origin_id"));row.put("receivedAt",rs.getTimestamp("received_at").toInstant().toString());return row;
        },order,product);
        for(var row:rows){long used=jdbc.queryForList("SELECT quantity FROM wh_return_receipt WHERE allocation_id=?"+(lock?" FOR UPDATE":""),Long.class,row.get("id")).stream().mapToLong(Long::longValue).sum();if(used>number(row,"quantity"))throw WarehouseException.conflict("Returned source totals are inconsistent");row.put("returned",used);row.put("remaining",number(row,"quantity")-used);}return rows;
    }
    private void charge(Map<String,Object> head,String receipt,long quantity){
        var rates=WarehouseJson.map(WarehouseJson.write(head.get("rates")));BigDecimal first=ReturnInput.rate(rates.get("returnFirst")),additional=ReturnInput.rate(rates.get("returnAdditional"));boolean initial=number(head,"received")==0;
        long received=number(head,"received");BigDecimal prior=initial?BigDecimal.ZERO:first.add(additional.multiply(BigDecimal.valueOf(received-1)));
        BigDecimal cumulative=first.add(additional.multiply(BigDecimal.valueOf(received+quantity-1))),raw=cumulative.subtract(prior);
        var amount=BillingCalculator.moneyRound(cumulative).subtract(BillingCalculator.moneyRound(prior));String id=UUID.randomUUID().toString(),source="return:"+text(head,"id")+":"+receipt;
        var data=new LinkedHashMap<String,Object>();for(String key:List.of("client","warehouse","currency","reference"))data.put(key,head.get(key));data.put("returnId",head.get("id"));data.put("type","Returns");data.put("status","UNCONFIRMED");data.put("description","Return / 退货 "+text(head,"reference"));data.put("amount",amount);data.put("source",source);data.put("breakdown",List.of(Map.of("description","Returned items / 退货处理","quantity",quantity,"firstApplied",initial,"firstRate",first,"additionalRate",additional,"amount",amount)));
        var saved=records.write("charges",id,data,null,source,text(head,"client"));records.replaceReferences(id,Map.of("return",text(head,"id"),"client",text(head,"client"),"warehouse",text(head,"warehouse")));
        jdbc.update("INSERT INTO wh_finance_source(source_key,charge_id,evidence_json) VALUES(?,?,?)",source,id,WarehouseJson.write(Map.of("version",1,"returnId",head.get("id"),"receiptId",receipt,"rates",rates,"receivedBefore",head.get("received"),"quantity",quantity,"amount",amount,"raw",raw)));
        records.audit("ASSESS_RETURN","charges",id,null,saved);
    }
    private Map<String,Object> view(Map<String,Object> head){
        var out=WarehouseJson.copy(head);out.put("sources",sources(text(head,"order"),text(head,"product"),false));out.put("clientName",records.get("clients",text(head,"client"),false).get("shortName"));out.put("warehouseName",records.get("warehouses",text(head,"warehouse"),false).get("name"));
        out.put("held",number(head,"received")-number(head,"restocked")-number(head,"scrapped"));
        out.put("receipts",jdbc.query("SELECT r.*,loc.display_name location_name,i.batch_number,i.serial_number,i.expiry_date FROM wh_return_receipt r JOIN wh_record loc ON loc.id=r.location_id JOIN wh_order_allocation a ON a.id=r.allocation_id JOIN wh_inventory i ON i.id=a.inventory_id WHERE r.return_id=? ORDER BY r.received_at,r.id",(rs,n)->{
            var row=new LinkedHashMap<String,Object>();for(String[] p:List.of(new String[]{"id","id"},new String[]{"allocation_id","allocation"},new String[]{"location_id","location"},new String[]{"location_name","locationName"},new String[]{"layer_id","layerId"},new String[]{"inspection","inspection"},new String[]{"inspection_reason","inspectionReason"},new String[]{"batch_number","batch"},new String[]{"serial_number","serial"},new String[]{"expiry_date","expiry"}))row.put(p[1],rs.getString(p[0]));row.put("quantity",rs.getLong("quantity"));row.put("disposed",rs.getLong("disposed_quantity"));row.put("remaining",rs.getLong("quantity")-rs.getLong("disposed_quantity"));row.put("receivedBy",rs.getLong("received_by"));row.put("receivedAt",rs.getTimestamp("received_at").toInstant().toString());row.put("inspectedBy",rs.getObject("inspected_by"));row.put("inspectedAt",rs.getTimestamp("inspected_at")==null?null:rs.getTimestamp("inspected_at").toInstant().toString());return row;
        },head.get("id")));
        out.put("dispositions",jdbc.query("SELECT d.*,l.display_name location_name FROM wh_return_disposition d JOIN wh_return_receipt r ON r.id=d.receipt_id LEFT JOIN wh_record l ON l.id=d.location_id WHERE r.return_id=? ORDER BY d.created_at,d.id",(rs,n)->{var row=new LinkedHashMap<String,Object>();row.put("id",rs.getString("id"));row.put("receipt",rs.getString("receipt_id"));row.put("action",rs.getString("action_name"));row.put("quantity",rs.getLong("quantity"));row.put("locationName",rs.getString("location_name"));row.put("reason",rs.getString("reason"));row.put("actor",rs.getLong("actor_id"));row.put("createdAt",rs.getTimestamp("created_at").toInstant().toString());row.put("result",rs.getString("result_json")==null?null:WarehouseJson.map(rs.getString("result_json")));return row;},head.get("id")));return out;
    }
    private Map<String,Object> claim(String request,String type,String hash){jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,type,hash,"{}");return jdbc.queryForObject("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE",(rs,n)->{if(!type.equals(rs.getString(1))||!hash.equals(rs.getString(2)))throw WarehouseException.conflict("Request ID already used / 请求编号已用于其他操作");var response=WarehouseJson.map(rs.getString(3));return response.isEmpty()?null:response;},scope.actor(),request);}
    private Map<String,Object> complete(String request,String hash,Map<String,Object> result){if(jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?",WarehouseJson.write(result),scope.actor(),request,hash)!=1)throw WarehouseException.conflict("Return journal changed");return result;}
    private void lockLocations(String warehouse){jdbc.queryForList("SELECT id FROM wh_record WHERE kind='locations' AND warehouse_id=? ORDER BY id FOR UPDATE",String.class,warehouse);}
    private static void shipped(Map<String,Object> order){if(!"JAVA_MANUAL_V1".equals(order.get("workflow"))||!"DESPATCHED".equals(order.get("status")))throw WarehouseException.conflict("Only a native despatched order can be returned / 只能关联已有完整来源的已发货订单");}
    private static Map<String,Object> business(Map<String,Object> value){var out=WarehouseJson.copy(value);for(String key:List.of("id","_version","createdAt","updatedAt"))out.remove(key);return out;}
    private static String text(Map<String,Object> value,String key){return WarehouseJson.text(value,key);}
    private static long number(Map<String,Object> value,String key){return ((Number)value.get(key)).longValue();}
}
