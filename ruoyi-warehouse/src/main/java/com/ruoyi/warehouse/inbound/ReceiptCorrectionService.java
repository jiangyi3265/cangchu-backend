package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.inventory.*;
import com.ruoyi.warehouse.finance.FinanceReviewService;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptCorrectionService {
    private final WarehouseRepository records;private final WarehouseScope scope;private final InventoryService stock;private final InboundService inbound;private final JdbcTemplate jdbc;
    public ReceiptCorrectionService(WarehouseRepository records,WarehouseScope scope,InventoryService stock,InboundService inbound){this.records=records;this.scope=scope;this.stock=stock;this.inbound=inbound;jdbc=records.jdbc();}
    @Transactional(readOnly=true) public Map<String,Object> detail(String asn,String id){var row=get(id,false);if(!asn.equals(row.get("asn")))throw WarehouseException.missing();access(row);return row;}
    @Transactional public Map<String,Object> context(String id,String line){var asn=lockAsn(id);return new ReceiptStockGraph(records,scope).read(asn,line);}
    @Transactional public Map<String,Object> preview(String id,Map<String,Object> input){InboundRequest.keys(input,Set.of("_version","receiptLine","allocations","reason","evidenceReference"));var asn=lockAsn(id);InboundService.current(asn,input);return proposal(asn,input);}
    @Transactional public Map<String,Object> approve(String id,Map<String,Object> input){
        InboundRequest.keys(input,Set.of("requestId","_version","receiptLine","allocations","reason","evidenceReference","quoteHash"));records.scoped("asns",id,false);
        String request=InboundRequest.id(input,"requestId"),digest=InboundRequest.hash(Map.of("asn",id,"input",input));var replay=inbound.claim(request,"ASN_CORRECTION_APPROVE",digest);if(replay!=null){access(replay);return replay;}
        var asn=lockAsn(id);InboundService.current(asn,input);var plan=proposal(asn,input);
        if(!Objects.equals(plan.get("quoteHash"),input.get("quoteHash")))throw WarehouseException.conflict("Receipt correction preview changed; preview again / 收货更正预览已变化，请重新预览");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_correction WHERE asn_id=?",Long.class,id)>=500)throw WarehouseException.conflict("Receipt correction capacity reached / 收货更正记录已达上限");
        String correction=UUID.randomUUID().toString();var approval=new LinkedHashMap<>(plan);approval.put("id",correction);approval.put("asn",id);approval.put("approvedBy",scope.actor());approval.put("approvedAt",Instant.now().toString());
        String json=WarehouseJson.write(approval);if(json.length()>2000000)throw WarehouseException.invalid("Correction evidence exceeds capacity / 更正证据超出容量");
        jdbc.update("INSERT INTO wh_asn_correction(id,asn_id,receipt_line_id,quantity,revision,status,approved_by,approval_json,approval_hash,result_json,result_hash) VALUES(?,?,?,?,1,'APPROVED',?,?,?,?,?)",correction,id,plan.get("receiptLine"),plan.get("quantity"),scope.actor(),json,InboundRequest.hash(approval),"{}",InboundRequest.hash(Map.of()));
        var saved=get(correction,false);records.audit("APPROVE_RECEIPT_CORRECTION","asns",id,null,saved);return inbound.complete(request,digest,saved);
    }
    @Transactional public Map<String,Object> action(String id,String correction,String action,Map<String,Object> input){
        if(!Set.of("execute","cancel").contains(action))throw WarehouseException.invalid("Unknown correction action");
        InboundRequest.keys(input,"execute".equals(action)?Set.of("requestId","_version","reason","confirmRecordedError"):Set.of("requestId","_version","reason"));String reason=InboundRequest.text(input,"reason",500,true),request=InboundRequest.id(input,"requestId");
        records.scoped("asns",id,false);var visible=get(correction,false);if(!id.equals(visible.get("asn")))throw WarehouseException.missing();access(visible);
        String digest=InboundRequest.hash(Map.of("asn",id,"correction",correction,"action",action,"input",input));var replay=inbound.claim(request,"ASN_CORRECTION_"+action.toUpperCase(Locale.ROOT),digest);if(replay!=null){access(replay);return replay;}
        var before=lockAsn(id);var old=get(correction,true);if(((Number)old.get("_version")).longValue()!=InboundRequest.version(input)||!"APPROVED".equals(old.get("status")))throw WarehouseException.conflict("Correction approval changed / 更正批准已变化");
        var approval=map(old,"approval");var result=new LinkedHashMap<String,Object>();result.put("reason",reason);result.put("actor",scope.actor());result.put("at",Instant.now().toString());String status="CANCELLED";
        if("execute".equals(action)){
            if(((Number)old.get("approvedBy")).longValue()==scope.actor())throw new WarehouseException(403,"warehouse_separation_required","Another person must execute the approved correction / 请由另一人执行已批准更正");
            if(!Boolean.TRUE.equals(input.get("confirmRecordedError")))throw WarehouseException.invalid("Confirm this removes a recording error, not physically dispatched or disposed goods / 请确认这是收货记录多录，非实际发货、退供或报废");
            var planInput=map(approval,"planInput");InboundService.current(before,planInput);var fresh=proposal(before,planInput);
            if(!Objects.equals(fresh.get("quoteHash"),approval.get("quoteHash")))throw WarehouseException.conflict("Approved source stock changed; cancel and preview again / 已批准的来源库存已变化，请撤销后重新预览");
            stock.correctReceipt(correction,before,text(approval,"product"),WarehouseJson.maps(approval.get("allocations")),reason);
            var data=InboundService.business(before);var accepted=InboundService.received(before);String product=text(approval,"product");long quantity=((Number)approval.get("quantity")).longValue(),net=accepted.getOrDefault(product,0L)-quantity;
            if(net<0)throw WarehouseException.conflict("Corrected receipt total would be negative / 更正后实收数量不能为负");accepted.put(product,net);data.put("received",accepted);
            if(!"CLOSED".equals(before.get("status")))data.put("status",accepted.values().stream().allMatch(q->q==0)?"NEW":InboundService.expected(data).entrySet().stream().allMatch(e->accepted.getOrDefault(e.getKey(),0L)>=e.getValue())?"RECEIVED":"PARTIALLY_RECEIVED");
            // Original receipt/closure evidence stays in SQL and the approved before snapshot.
            data.put("lastCorrectionAt",result.get("at"));var saved=inbound.write(id,before,data);result.put("asnVersion",saved.get("_version"));result.put("netAccepted",accepted);status="EXECUTED";
        }
        String json=WarehouseJson.write(result);if(jdbc.update("UPDATE wh_asn_correction SET status=?,revision=revision+1,result_json=?,result_hash=? WHERE id=? AND revision=? AND status='APPROVED'",status,json,InboundRequest.hash(result),correction,old.get("_version"))!=1)throw WarehouseException.conflict("Correction changed");
        if("EXECUTED".equals(status))FinanceReviewService.enqueueReceiptCorrection(records,scope,correction,approval);
        var saved=get(correction,false);records.audit("ASN_CORRECTION_"+action.toUpperCase(Locale.ROOT),"asns",id,old,saved);return inbound.complete(request,digest,saved);
    }
    private Map<String,Object> proposal(Map<String,Object> asn,Map<String,Object> input){
        String line=InboundRequest.id(input,"receiptLine"),reason=InboundRequest.text(input,"reason",500,true),reference=InboundRequest.text(input,"evidenceReference",200,true);var graph=new ReceiptStockGraph(records,scope).read(asn,line);var receipt=map(graph,"receipt");
        if(!(input.get("allocations") instanceof List<?> values)||values.isEmpty()||values.size()>200)throw WarehouseException.invalid("Select source quantities to correct / 请选择需更正的来源数量");
        var locationIds=new TreeSet<String>();for(Object raw:values){var item=WarehouseJson.map(WarehouseJson.write(raw));for(var source:WarehouseJson.maps(graph.get("layers")))if(Objects.equals(item.get("layer"),source.get("id")))locationIds.add(text(source,"location_id"));}for(String location:locationIds)records.scoped("locations",location,true);
        var selected=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();long quantity=0;
        for(Object raw:values){var row=WarehouseJson.map(WarehouseJson.write(raw));InboundRequest.keys(row,Set.of("layer","quantity"));String layer=InboundRequest.id(row,"layer");long q=InboundRequest.quantity(row.get("quantity"));var source=WarehouseJson.maps(graph.get("layers")).stream().filter(r->layer.equals(r.get("id"))).findFirst().orElseThrow(WarehouseException::missing);
            if(!seen.add(layer)||q>((Number)source.get("remaining")).longValue()-((Number)source.get("reserved")).longValue())throw WarehouseException.conflict("Source is duplicated, shipped, reserved or insufficient / 来源重复、已发货、已预留或可更正数量不足");
            StocktakeLock.available(jdbc,text(asn,"client"),text(source,"warehouse_id"),text(source,"location_id"));var location=records.scoped("locations",text(source,"location_id"),true);if(WarehouseJson.enabled(location.get("custodyOnly"))||"Quarantine".equals(location.get("type")))throw WarehouseException.conflict("Held goods require their custody workflow / 保管货物需使用对应的实物处理流程");
            selected.add(Map.of("layer",layer,"quantity",q,"inventory",source.get("inventory_id"),"warehouse",source.get("warehouse_id"),"location",source.get("location_id")));quantity=Math.addExact(quantity,q);
        }
        if(quantity>((Number)graph.get("uncorrected")).longValue()||quantity>10000000)throw WarehouseException.conflict("Correction exceeds remaining original receipt / 更正超过原收货尚未更正数量");
        selected.sort(Comparator.comparing(r->String.valueOf(r.get("layer"))));var warehouses=new TreeSet<String>();warehouses.add(text(asn,"warehouse"));for(var source:WarehouseJson.maps(graph.get("layers")))warehouses.add(text(source,"warehouse_id"));
        var normalized=new LinkedHashMap<String,Object>();normalized.put("_version",asn.get("_version"));normalized.put("receiptLine",line);normalized.put("allocations",selected.stream().map(r->Map.of("layer",r.get("layer"),"quantity",r.get("quantity"))).toList());normalized.put("reason",reason);normalized.put("evidenceReference",reference);
        var plan=new LinkedHashMap<String,Object>();plan.put("planInput",normalized);plan.put("asnBefore",asn);plan.put("receiptLine",line);plan.put("product",receipt.get("product_id"));plan.put("receipt",receipt.get("receipt_id"));plan.put("client",asn.get("client"));plan.put("warehouse",asn.get("warehouse"));plan.put("warehouseIds",new ArrayList<>(warehouses));plan.put("quantity",quantity);plan.put("allocations",selected);plan.put("source",graph);plan.put("quoteHash",InboundRequest.hash(plan));return plan;
    }
    private Map<String,Object> lockAsn(String id){AsnLateSupplement.lockParent(records,id);var asn=records.scoped("asns",id,true);InboundService.nativeAsn(asn);if("CANCELLED".equals(asn.get("status")))throw WarehouseException.conflict("Cancelled ASN cannot be corrected / 已取消入库单不能更正收货");return asn;}
    private void access(Map<String,Object> row){records.scoped("asns",text(row,"asn"),false);for(Object warehouse:(List<?>)map(row,"approval").get("warehouseIds"))if(!scope.granted("WAREHOUSE",String.valueOf(warehouse)))throw WarehouseException.forbidden();}
    private Map<String,Object> get(String id,boolean lock){return read(jdbc,id,lock);}
    static Map<String,Object> read(JdbcTemplate jdbc,String id,boolean lock){
        var rows=jdbc.query("SELECT * FROM wh_asn_correction WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->{var approval=WarehouseJson.map(rs.getString("approval_json"));var result=WarehouseJson.map(rs.getString("result_json"));if(!InboundRequest.hash(approval).equals(rs.getString("approval_hash"))||!InboundRequest.hash(result).equals(rs.getString("result_hash"))||!id.equals(approval.get("id"))||!rs.getString("asn_id").equals(approval.get("asn"))||!rs.getString("receipt_line_id").equals(approval.get("receiptLine"))||rs.getLong("quantity")!=((Number)approval.get("quantity")).longValue()||rs.getLong("approved_by")!=((Number)approval.get("approvedBy")).longValue())throw WarehouseException.conflict("Correction evidence checksum mismatch / 更正证据校验失败");return Map.<String,Object>of("id",id,"asn",rs.getString("asn_id"),"_version",rs.getLong("revision"),"status",rs.getString("status"),"approvedBy",rs.getLong("approved_by"),"approval",approval,"result",result,"createdAt",rs.getTimestamp("created_at").toInstant().toString());},id);if(rows.size()!=1)throw WarehouseException.missing();return rows.get(0);
    }
    static List<Map<String,Object>> view(WarehouseRepository records,WarehouseScope scope,String id){var result=new ArrayList<Map<String,Object>>();for(String correction:records.jdbc().queryForList("SELECT id FROM wh_asn_correction WHERE asn_id=? ORDER BY created_at,id",String.class,id)){var row=read(records.jdbc(),correction,false);var approval=map(row,"approval");boolean allowed=((List<?>)approval.get("warehouseIds")).stream().allMatch(w->scope.granted("WAREHOUSE",String.valueOf(w)));if(!allowed)continue;var summary=new LinkedHashMap<String,Object>();for(String key:List.of("id","asn","_version","status","approvedBy","createdAt"))summary.put(key,row.get(key));for(String key:List.of("receiptLine","quantity","product"))summary.put(key,approval.get(key));var plan=map(approval,"planInput");summary.put("reason",plan.get("reason"));summary.put("evidenceReference",plan.get("evidenceReference"));summary.put("result",row.get("result"));result.add(summary);}return result;}
    private static String text(Map<String,Object> row,String key){return WarehouseJson.text(row,key);}
    private static Map<String,Object> map(Map<String,Object> row,String key){return WarehouseJson.map(WarehouseJson.write(row.get(key)));}
}
