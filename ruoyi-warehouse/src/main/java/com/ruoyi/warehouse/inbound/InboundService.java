package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.billing.BillingCalculator;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.data.SharedMasterReferences;
import com.ruoyi.warehouse.inventory.*;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Forecast, partial receipt, stock evidence and automatic charges share one transaction. */
@Service
public class InboundService {
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final InventoryService stock;
    private final JdbcTemplate jdbc;
    public InboundService(WarehouseRepository records, WarehouseScope scope, InventoryService stock) { this.records=records; this.scope=scope; this.stock=stock; jdbc=records.jdbc(); }

    @Transactional(readOnly=true)
    public Map<String,Object> list(int page,int size,String keyword,String client,String warehouse) {
        return records.list("asns",page,size,keyword,Map.of("client",client,"warehouse",warehouse));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> get(String id) { return view(records.scoped("asns",id,false)); }

    @Transactional
    public Map<String,Object> save(String id,Map<String,Object> input) {
        var data=InboundRequest.forecast(input,id!=null); checkScope(data);
        if(id!=null)records.scoped("asns",id,false);
        String request=InboundRequest.id(input,"requestId"), hash=InboundRequest.hash(Map.of("id",id==null?"":id,"input",input));
        var replay=claim(request,id==null?"ASN_CREATE":"ASN_UPDATE",hash);
        if(replay!=null){records.scoped("asns",text(replay,"id"),true);return replay;}
        if(id!=null)AsnLateSupplement.lockParent(records,id);
        Map<String,Object> before=id==null?null:records.scoped("asns",id,true);
        Long version=before==null?null:InboundRequest.version(input);
        if(before!=null) {
            nativeAsn(before); current(before,input);
            if(AsnLateSupplement.link(jdbc,id)!=null)throw WarehouseException.conflict("Supplement forecasts are fixed by approval; cancel an unreceived supplement and create a new one / 补单预报已获批准，不能改写；可取消未收货补单后重新申请");
            if(!"NEW".equals(before.get("status"))||receiptCount(id)>0||AsnCustody.count(jdbc,id)>0)throw WarehouseException.conflict("Only an unreceived ASN can be edited / 已签收的入库单不能修改预报");
            if(!data.get("client").equals(before.get("client"))||!data.get("warehouse").equals(before.get("warehouse")))throw WarehouseException.conflict("ASN client and warehouse cannot change / 入库单客户和仓库不能修改");
        }
        SharedMasterReferences.bind(records,"suppliers","supplierId","supplierName",data,before,input.containsKey("supplierId"));
        Context context=context(data,true);
        String asnId=id==null?UUID.randomUUID().toString():id;
        data.put("workflow","JAVA_ASN_V1");data.put("status","NEW");data.put("received",new LinkedHashMap<>());data.put("billedSkus",List.of());data.put("currency",currency(context.client()));data.put("fixedChargesBilled",false);
        var saved=records.write("asns",asnId,data,version,natural(data),text(data,"client"));
        var refs=new LinkedHashMap<String,String>();refs.put("client",text(data,"client"));refs.put("warehouse",text(data,"warehouse"));SharedMasterReferences.reference(refs,data,"supplierId");int n=0;for(String product:context.products().keySet())refs.put("product"+n++,product);records.replaceReferences(asnId,refs);
        records.audit(id==null?"ASN_CREATE":"ASN_UPDATE","asns",asnId,before,saved);
        return complete(request,hash,view(saved));
    }

    @Transactional
    public Map<String,Object> lateSupplement(String id,Map<String,Object> input){
        InboundRequest.keys(input,Set.of("requestId","_version","reference","expectedDate","deliveryType","notes","lines","adminPolicy","skuPolicy","approveAdditional","reason"));
        InboundRequest.version(input);String request=InboundRequest.id(input,"requestId"),reason=InboundRequest.text(input,"reason",500,true),admin=InboundRequest.text(input,"adminPolicy",16,true),sku=InboundRequest.text(input,"skuPolicy",16,true);
        if(!Set.of("included","separate").contains(admin)||!Set.of("group","separate").contains(sku))throw WarehouseException.invalid("Choose both supplement charging policies / 请明确补单管理费与 SKU 计费方式");
        if(input.containsKey("approveAdditional")&&!(input.get("approveAdditional") instanceof Boolean))throw WarehouseException.invalid("Additional approval must be boolean");
        String hash=InboundRequest.hash(Map.of("asn",id,"input",input));records.scoped("asns",id,false);
        var replay=claim(request,"ASN_LATE_SUPPLEMENT",hash);if(replay!=null)return replay;
        // Only original closed ASNs own supplement families. Never reopen or revise them.
        if(AsnLateSupplement.link(jdbc,id)!=null)throw WarehouseException.conflict("Create further supplements from the original ASN / 请从原入库单创建后续补单");
        var parent=records.scoped("asns",id,true);nativeAsn(parent);current(parent,input);
        if(!"CLOSED".equals(parent.get("status")))throw WarehouseException.conflict("Late supplements require a closed original ASN / 迟到补单仅适用于已结案原单");
        var children=AsnLateSupplement.children(records,id,true);if(children.size()>=250)throw WarehouseException.conflict("Supplement count limit reached / 关联补单已达上限");
        var raw=new LinkedHashMap<>(input);raw.keySet().removeAll(Set.of("_version","adminPolicy","skuPolicy","approveAdditional","reason"));raw.put("client",parent.get("client"));raw.put("warehouse",parent.get("warehouse"));var data=InboundRequest.forecast(raw,false);SharedMasterReferences.bind(records,"suppliers","supplierId","supplierName",data,parent,false);
        var remaining=AsnLateSupplement.remaining(parent,children);var matches=new ArrayList<Map<String,Object>>();
        for(var line:WarehouseJson.maps(data.get("lines"))){String product=text(line,"product");if(!remaining.containsKey(product))throw WarehouseException.invalid("Supplement product must exist on the original ASN / 补单商品必须属于原预报，未知商品请先核对");long quantity=((Number)line.get("quantity")).longValue(),matched=Math.min(quantity,remaining.get(product));
            if(quantity>matched&&!Boolean.TRUE.equals(input.get("approveAdditional")))throw WarehouseException.conflict("Quantity exceeds unallocated shortfall; explicitly approve additional goods / 数量超出原单尚未关联的短收，请明确批准额外数量");
            matches.add(Map.of("product",product,"quantity",quantity,"matchedShortfall",matched,"additionalApproved",quantity-matched));
        }
        var context=context(data,true);sameCurrency(parent,context.client());String child=UUID.randomUUID().toString();
        data.put("workflow","JAVA_ASN_V1");data.put("status","NEW");data.put("received",new LinkedHashMap<>());data.put("billedSkus",List.of());data.put("currency",parent.get("currency"));data.put("fixedChargesBilled",false);
        var saved=records.write("asns",child,data,null,natural(data),text(data,"client"));var refs=new LinkedHashMap<String,String>();refs.put("parentAsn",id);refs.put("client",text(data,"client"));refs.put("warehouse",text(data,"warehouse"));SharedMasterReferences.reference(refs,data,"supplierId");int n=0;for(String product:context.products().keySet())refs.put("product"+n++,product);records.replaceReferences(child,refs);
        // Keep the closure snapshot distinct from the effective shortage after corrections.
        var evidence=Map.of("parentReference",parent.get("reference"),"parentVersion",parent.get("_version"),"parentClosedAt",parent.get("closedAt"),"parentShortfall",parent.get("shortfall"),"parentEffectiveShortfall",AsnLateSupplement.remaining(parent,List.of()),"parentUnallocatedShortfall",remaining,"lines",matches,"adminPolicy",admin,"skuPolicy",sku);
        jdbc.update("INSERT INTO wh_asn_late_link(child_asn,parent_asn,admin_policy,sku_policy,reason,actor_id,evidence_json) VALUES(?,?,?,?,?,?,?)",child,id,admin,sku,reason,scope.actor(),WarehouseJson.write(evidence));
        records.audit("ASN_LATE_SUPPLEMENT","asns",child,null,Map.of("asn",saved,"approval",evidence,"reason",reason));return complete(request,hash,view(saved));
    }

    @Transactional
    public Map<String,Object> receive(String id,Map<String,Object> input) {
        var visible=records.scoped("asns",id,false); var lines=InboundRequest.receipt(input,visible);
        String request=InboundRequest.id(input,"requestId"),hash=InboundRequest.hash(Map.of("asn",id,"input",input));
        var replay=claim(request,"ASN_RECEIVE",hash);if(replay!=null)return replay;
        AsnLateSupplement.lockParent(records,id);var before=records.scoped("asns",id,true);nativeAsn(before);current(before,input);open(before);
        AsnCustody.requireEmpty(jdbc,id);
        var asn=business(before);Context context=context(asn,true);sameCurrency(asn,context.client());
        if(receiptCount(id)>=250||jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_receipt_line l JOIN wh_asn_receipt r ON r.id=l.receipt_id WHERE r.asn_id=?",Long.class,id)+lines.size()>2000)throw WarehouseException.conflict("ASN receipt limit reached / 单据收货记录已达上限");
        Map<String,Long> received=received(asn),expected=expected(asn);var locations=new TreeSet<String>();
        for(var line:lines) {
            if(!expected.containsKey(line.product()))throw WarehouseException.invalid("Product is not on this ASN / 商品不在本入库单内");
            long next=received.getOrDefault(line.product(),0L)+line.quantity();
            if(next>expected.get(line.product()))throw WarehouseException.conflict("Over-receipt requires a separate review / 实收超出预报，不能直接入库");
            received.put(line.product(),next);locations.add(line.location());
            var product=context.products().get(line.product());line.validateTracking(product);
            if(!line.expiry().isEmpty()&&LocalDate.parse(line.expiry()).isBefore(LocalDate.now(ZoneOffset.UTC)))throw WarehouseException.conflict("Expired goods need an exception workflow / 过期货物需走异常处理流程");
        }
        if(received.values().stream().mapToLong(Long::longValue).sum()>10_000_000)throw WarehouseException.conflict("ASN accepted quantity limit reached");
        // Match the inventory/order lock order before touching any stock pool.
        for(String location:locations)records.get("locations",location,true);
        var pricedLines=lines.stream().map(line->Map.<String,Object>of("product",line.product(),"quantity",line.quantity())).toList();
        var basis=AsnLateSupplement.pricing(records,id,before,context.client());validateUnitRates(basis.asn(),new Context(basis.client(),context.products()),pricedLines);
        var pricing=basis.evidence();pricing.put("products",new ArrayList<>(context.products().values()));pricing.put("received",pricedLines);pricing.put("complete",false);
        var quote=quote(pricing);String receipt=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wh_asn_receipt(id,asn_id,actor_id) VALUES(?,?,?)",receipt,id,scope.actor());
        for(var line:lines) {
            var evidence=stock.receiveAsn(id,receipt,line);
            jdbc.update("INSERT INTO wh_asn_receipt_line(id,receipt_id,product_id,location_id,inventory_id,layer_id,quantity,batch_number,serial_number,expiry_date) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),receipt,line.product(),line.location(),evidence.get("inventoryId"),evidence.get("layerId"),line.quantity(),line.batch(),line.serial(),line.expiry());
        }
        postCharge(id,receipt,asn,quote,pricing);
        asn.put("received",received);asn.put("billedSkus",quote.get("billedSkus"));asn.put("status",expected.entrySet().stream().allMatch(e->received.getOrDefault(e.getKey(),0L)>=e.getValue())?"RECEIVED":"PARTIALLY_RECEIVED");asn.put("lastReceivedAt",Instant.now().toString());
        var saved=write(id,before,asn);records.audit("ASN_RECEIVE","asns",id,before,Map.of("asn",saved,"receiptId",receipt));
        return complete(request,hash,view(saved));
    }

    @Transactional
    public Map<String,Object> close(String id,Map<String,Object> input) {
        InboundRequest.keys(input,Set.of("requestId","_version","deliveryType","deliveryQuantity","hours","approveShortfall","reason"));
        String type=InboundRequest.text(input,"deliveryType",40,true);if(!InboundRequest.DELIVERY_TYPES.contains(type))throw WarehouseException.invalid("Invalid delivery type");
        long delivery=InboundRequest.quantity(input.get("deliveryQuantity"));var hours=InboundRequest.decimal(input.get("hours"),"Labour hours");if(hours.compareTo(new BigDecimal("100000"))>0||hours.scale()>3)throw WarehouseException.invalid("Hours must have at most three decimal places, up to 100000");
        if(input.containsKey("approveShortfall")&&!(input.get("approveShortfall") instanceof Boolean))throw WarehouseException.invalid("Shortfall approval must be boolean");
        String reason=InboundRequest.text(input,"reason",500,false),request=InboundRequest.id(input,"requestId"),hash=InboundRequest.hash(Map.of("asn",id,"input",input));InboundRequest.version(input);records.scoped("asns",id,false);
        var replay=claim(request,"ASN_CLOSE",hash);if(replay!=null)return replay;
        AsnLateSupplement.lockParent(records,id);var before=records.scoped("asns",id,true);nativeAsn(before);current(before,input);
        if(!Set.of("PARTIALLY_RECEIVED","RECEIVED").contains(before.get("status"))&&!("NEW".equals(before.get("status"))&&(AsnCustody.count(jdbc,id)>0||receiptCount(id)>0)))throw WarehouseException.conflict("Receive goods before closing this ASN / 请先签收货物再结案");
        AsnCustody.requireEmpty(jdbc,id);
        boolean shortfall=expected(before).entrySet().stream().anyMatch(e->received(before).getOrDefault(e.getKey(),0L)<e.getValue());
        if(shortfall){scope.requireAdministrator();if(!Boolean.TRUE.equals(input.get("approveShortfall"))||reason.isBlank())throw WarehouseException.conflict("Shortfall closure needs administrator approval and a reason / 短收结案需要管理员确认并填写原因");}
        var asn=business(before);var client=records.scoped("clients",text(asn,"client"),true);sameCurrency(asn,client);
        asn.put("deliveryType",type);asn.put("deliveryQuantity",delivery);asn.put("hours",hours);asn.put("closureReason",reason);
        String rateKey=switch(type){case "FCL Container 20"->"goodsIn20";case "FCL Container 40"->"goodsIn40";case "FCL Container 40 HC"->"goodsIn40HC";case "FCL Container 45"->"goodsIn45";case "FCL Container 45 HC"->"goodsIn45HC";case "LCL Pallet"->"goodsInPallet";default->"goodsInCarton";};
        var basis=AsnLateSupplement.pricing(records,id,asn,client);
        InboundRequest.decimal(client.get(rateKey),rateKey);InboundRequest.decimal(basis.client().get("asnAdmin"),"asnAdmin");if(hours.signum()>0)InboundRequest.decimal(client.get("asnHour"),"asnHour");
        var pricing=basis.evidence();pricing.put("products",List.of());pricing.put("received",List.of());pricing.put("complete",true);
        postCharge(id,"closure",asn,quote(pricing),pricing);
        var deficits=new LinkedHashMap<String,Long>();expected(asn).forEach((p,q)->deficits.put(p,Math.max(0,q-received(asn).getOrDefault(p,0L))));
        asn.put("shortfall",deficits);asn.put("shortfallApprovedBy",shortfall?scope.actor():null);asn.put("fixedChargesBilled",true);asn.put("status","CLOSED");asn.put("closedAt",Instant.now().toString());
        var saved=write(id,before,asn);records.audit("ASN_CLOSE","asns",id,before,saved);return complete(request,hash,view(saved));
    }

    @Transactional
    public Map<String,Object> cancel(String id,Map<String,Object> input) {
        InboundRequest.keys(input,Set.of("requestId","_version","reason"));String reason=InboundRequest.text(input,"reason",500,true),request=InboundRequest.id(input,"requestId"),hash=InboundRequest.hash(Map.of("asn",id,"input",input));InboundRequest.version(input);records.scoped("asns",id,false);
        var replay=claim(request,"ASN_CANCEL",hash);if(replay!=null)return replay;
        AsnLateSupplement.lockParent(records,id);var before=records.scoped("asns",id,true);nativeAsn(before);current(before,input);
        if(!"NEW".equals(before.get("status"))||receiptCount(id)>0||AsnCustody.count(jdbc,id)>0)throw WarehouseException.conflict("Received goods cannot be cancelled through this action / 已签收的入库单不能直接取消");
        var asn=business(before);asn.put("status","CANCELLED");asn.put("cancelReason",reason);asn.put("cancelledAt",Instant.now().toString());var saved=write(id,before,asn);records.audit("ASN_CANCEL","asns",id,before,saved);return complete(request,hash,view(saved));
    }

    Context context(Map<String,Object> asn,boolean active) {
        checkScope(asn);var products=new TreeMap<String,Map<String,Object>>();
        for(String id:expected(asn).keySet().stream().sorted().toList()){
            var p=records.scoped("products",id,true);
            if(!Objects.equals(asn.get("client"),p.get("client")))throw WarehouseException.forbidden();
            if(active&&(inactive(p)||enabled(p,"bundle")||enabled(p,"dropship")||enabled(p,"infiniteStock")))throw WarehouseException.conflict("Only active physical products can be received / 只能接收已启用的实物商品");
            products.put(id,p);
        }
        var client=records.scoped("clients",text(asn,"client"),true);var warehouse=records.scoped("warehouses",text(asn,"warehouse"),true);
        if(active&&(inactive(client)||enabled(client,"onStop")||enabled(client,"infiniteStock")||inactive(warehouse)))throw WarehouseException.conflict("Client or warehouse is unavailable for receiving / 客户或仓库不能接收库存");
        var restrictions=jdbc.queryForList("SELECT business_json FROM wh_record WHERE kind='productWarehouses' ORDER BY id FOR UPDATE",String.class).stream().map(WarehouseJson::map).filter(r->!inactive(r)).toList();
        for(String product:products.keySet())if(restrictions.stream().anyMatch(r->product.equals(r.get("product")))&&restrictions.stream().noneMatch(r->product.equals(r.get("product"))&&asn.get("warehouse").equals(r.get("warehouse"))))throw WarehouseException.conflict("Product is restricted to a different warehouse / 商品不允许存入当前仓库");
        return new Context(client,products);
    }
    private void validateUnitRates(Map<String,Object> asn,Context context,List<Map<String,Object>> received) {
        Set<Object> billed=new HashSet<>((List<?>)asn.getOrDefault("billedSkus",List.of()));
        for(var line:received){var p=context.products().get(text(line,"product"));if(enabled(p,"noGoodsIn"))continue;
            InboundRequest.decimal(WarehouseJson.empty(p.get("goodsInRate"))?context.client().get("goodsInRate"):p.get("goodsInRate"),"goodsInRate");
            if(!billed.contains(p.get("id")))InboundRequest.decimal(WarehouseJson.empty(p.get("goodsInSku"))?context.client().get("goodsInSku"):p.get("goodsInSku"),"goodsInSku");
        }
    }
    private Map<String,Object> quote(Map<String,Object> input){try{return BillingCalculator.goodsInQuote(input);}catch(IllegalArgumentException error){throw WarehouseException.conflict("ASN pricing requires correction: "+error.getMessage());}}
    private void postCharge(String id,String event,Map<String,Object> asn,Map<String,Object> quote,Map<String,Object> pricing) {
        var amount=InboundRequest.decimal(quote.get("amount"),"Charge amount");String source="asn:"+id+":"+event,chargeId=UUID.randomUUID().toString();
        var charge=new LinkedHashMap<String,Object>();charge.put("client",asn.get("client"));charge.put("warehouse",asn.get("warehouse"));charge.put("asnId",id);charge.put("reference",asn.get("reference"));charge.put("description",("closure".equals(event)?"ASN closure / 入库结案 ":"Goods in / 入库收货 ")+text(asn,"reference"));charge.put("type","Goods In");charge.put("currency",asn.get("currency"));charge.put("status","UNCONFIRMED");charge.put("amount",amount);charge.put("breakdown",quote.get("lines"));charge.put("source",source);
        var saved=records.write("charges",chargeId,charge,null,source,text(asn,"client"));
        records.replaceReferences(chargeId,Map.of("asn",id,"client",text(asn,"client"),"warehouse",text(asn,"warehouse")));
        jdbc.update("INSERT INTO wh_finance_source(source_key,charge_id,evidence_json) VALUES(?,?,?)",source,chargeId,WarehouseJson.write(Map.of("version",1,"recordedAt",Instant.now().toString(),"context",pricing,"quote",quote)));
        records.audit("ASSESS_ASN","charges",chargeId,null,saved);
    }
    /** Records accepted custody once, using the same unit/SKU accounting as direct receipts. */
    void acceptCustody(String id,String task,String event,Map<String,Object> before,Map<String,Object> asn,Context context,Map<String,Object> held,long quantity,String location,Map<String,Object> evidence){
        if(receiptCount(id)>=250||jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_receipt_line l JOIN wh_asn_receipt r ON r.id=l.receipt_id WHERE r.asn_id=?",Long.class,id)>=2000)throw WarehouseException.conflict("ASN receipt limit reached / 单据收货记录已达上限");
        sameCurrency(asn,context.client());String product=String.valueOf(held.get("product_id"));
        var received=received(asn);received.merge(product,quantity,Long::sum);
        if(received.values().stream().mapToLong(Long::longValue).sum()>10_000_000)throw WarehouseException.conflict("ASN accepted quantity limit reached");
        var pricedLines=List.of(Map.<String,Object>of("product",product,"quantity",quantity));var basis=AsnLateSupplement.pricing(records,id,before,context.client());validateUnitRates(basis.asn(),new Context(basis.client(),context.products()),pricedLines);
        var pricing=basis.evidence();pricing.put("products",new ArrayList<>(context.products().values()));pricing.put("received",pricedLines);pricing.put("complete",false);pricing.put("custodyTask",task);pricing.put("custodyEvent",event);
        var quote=quote(pricing);String receipt=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO wh_asn_receipt(id,asn_id,actor_id) VALUES(?,?,?)",receipt,id,scope.actor());
        jdbc.update("INSERT INTO wh_asn_receipt_line(id,receipt_id,product_id,location_id,inventory_id,layer_id,quantity,batch_number,serial_number,expiry_date) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),receipt,product,location,evidence.get("inventoryId"),evidence.get("layerId"),quantity,held.get("batch_number"),held.get("serial_number"),held.get("expiry_date"));
        postCharge(id,receipt,asn,quote,pricing);asn.put("received",received);asn.put("billedSkus",quote.get("billedSkus"));
        asn.put("status",expected(asn).entrySet().stream().allMatch(e->received.getOrDefault(e.getKey(),0L)>=e.getValue())?"RECEIVED":"PARTIALLY_RECEIVED");asn.put("lastReceivedAt",Instant.now().toString());
        evidence.put("receiptId",receipt);
    }

    Map<String,Object> view(Map<String,Object> value) {
        var out=WarehouseJson.copy(value);var received=received(value);var lines=new ArrayList<Map<String,Object>>();
        var original=WarehouseJson.maps(value.get("lines"));for(var entry:expected(value).entrySet()){String productId=entry.getKey();var row=new LinkedHashMap<String,Object>();var forecast=original.stream().filter(l->productId.equals(l.get("product"))).findFirst();row.put("product",productId);row.put("quantity",forecast.map(l->l.get("quantity")).orElse(0L));row.put("unexpected",forecast.isEmpty());var product=records.get("products",productId,false);long count=received.getOrDefault(productId,0L),expected=entry.getValue();row.put("received",count);row.put("remaining",Math.max(0,expected-count));row.put("overage",Math.max(0,count-expected));row.put("sku",product.get("sku"));row.put("productName",product.get("name"));lines.add(row);}out.put("lines",lines);
        out.put("clientName",records.get("clients",text(value,"client"),false).get("shortName"));out.put("warehouseName",records.get("warehouses",text(value,"warehouse"),false).get("name"));
        out.put("receipts",jdbc.query("SELECT r.id receipt_id,r.received_at,r.actor_id,l.*,p.display_name sku,loc.display_name location_name FROM wh_asn_receipt r JOIN wh_asn_receipt_line l ON l.receipt_id=r.id JOIN wh_record p ON p.id=l.product_id JOIN wh_record loc ON loc.id=l.location_id WHERE r.asn_id=? ORDER BY r.received_at,r.id,l.id",(rs,n)->{var row=new LinkedHashMap<String,Object>();for(String[] pair:List.of(new String[]{"id","receiptLineId"},new String[]{"receipt_id","receiptId"},new String[]{"product_id","product"},new String[]{"location_id","location"},new String[]{"location_name","locationName"},new String[]{"sku","sku"},new String[]{"layer_id","layerId"},new String[]{"batch_number","batch"},new String[]{"serial_number","serial"},new String[]{"expiry_date","expiry"}))row.put(pair[1],rs.getString(pair[0]));row.put("quantity",rs.getLong("quantity"));row.put("receivedAt",rs.getTimestamp("received_at").toInstant().toString());row.put("actorId",rs.getLong("actor_id"));return row;},value.get("id")));
        out.put("receiptCorrections",ReceiptCorrectionService.view(records,scope,text(value,"id")));out.put("custodyTasks",AsnCustody.tasks(jdbc,value));out.put("custodyEvents",AsnCustody.events(jdbc,text(value,"id")));out.put("supplierReturnApprovals",AsnSupplierReturn.view(jdbc,text(value,"id")));
        AsnLateSupplement.expose(records,value,out);AsnUnidentified.expose(jdbc,text(value,"id"),out);out.remove("billedSkus");return out;
    }
    Map<String,Object> write(String id,Map<String,Object> old,Map<String,Object> data){return records.write("asns",id,data,((Number)old.get("_version")).longValue(),natural(data),text(data,"client"));}
    Map<String,Object> claim(String request,String type,String hash){jdbc.update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,type,hash,"{}");return jdbc.queryForObject("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE",(rs,n)->{if(!type.equals(rs.getString(1))||!hash.equals(rs.getString(2)))throw WarehouseException.conflict("Request ID was already used for another operation / 请求编号已用于其他操作");var response=WarehouseJson.map(rs.getString(3));return response.isEmpty()?null:response;},scope.actor(),request);}
    Map<String,Object> complete(String request,String hash,Map<String,Object> response){if(jdbc.update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?",WarehouseJson.write(response),scope.actor(),request,hash)!=1)throw WarehouseException.conflict("Request journal changed");return response;}
    private long receiptCount(String id){return jdbc.queryForObject("SELECT COUNT(*) FROM wh_asn_receipt WHERE asn_id=?",Long.class,id);}
    private void checkScope(Map<String,Object> asn){if(!scope.granted("CLIENT",text(asn,"client"))||!scope.granted("WAREHOUSE",text(asn,"warehouse")))throw WarehouseException.forbidden();}
    static void current(Map<String,Object> row,Map<String,Object> in){if(((Number)row.get("_version")).longValue()!=InboundRequest.version(in))throw WarehouseException.conflict("ASN changed; reload before submitting / 入库单已更新，请刷新后重试");}
    static void nativeAsn(Map<String,Object> asn){if(!"JAVA_ASN_V1".equals(asn.get("workflow")))throw WarehouseException.conflict("This ASN needs its original workflow / 此入库单尚未迁移");}
    private static void open(Map<String,Object> asn){if(!Set.of("NEW","PARTIALLY_RECEIVED").contains(asn.get("status")))throw WarehouseException.conflict("ASN is not open for receiving / 当前状态不能继续收货");}
    static Map<String,Long> expected(Map<String,Object> asn){var out=new LinkedHashMap<String,Long>();for(var row:WarehouseJson.maps(asn.get("lines")))out.put(text(row,"product"),((Number)row.get("quantity")).longValue());for(Object product:(List<?>)asn.getOrDefault("exceptionProducts",List.of()))out.putIfAbsent(String.valueOf(product),0L);return out;}
    static Map<String,Long> received(Map<String,Object> asn){var out=new LinkedHashMap<String,Long>();if(asn.get("received") instanceof Map<?,?> values)values.forEach((k,v)->out.put(String.valueOf(k),((Number)v).longValue()));return out;}
    static Map<String,Object> business(Map<String,Object> row){var out=WarehouseJson.copy(row);for(String key:List.of("id","_version","createdAt","updatedAt"))out.remove(key);return out;}
    private static String natural(Map<String,Object> asn){return text(asn,"reference").toLowerCase(Locale.ROOT);}
    private static String currency(Map<String,Object> client){return WarehouseJson.empty(client.get("currency"))?"GBP":text(client,"currency");}
    private static void sameCurrency(Map<String,Object> asn,Map<String,Object> client){if(!currency(client).equals(asn.get("currency")))throw WarehouseException.conflict("Client currency changed; reconcile before receiving / 客户币种已改变，请先核对");}
    private static String text(Map<String,Object> row,String key){return WarehouseJson.text(row,key);}
    private static boolean enabled(Map<String,Object> row,String key){return WarehouseJson.enabled(row.get(key));}
    private static boolean inactive(Map<String,Object> row){return Boolean.FALSE.equals(row.get("active"))||"N".equals(row.get("active"));}
    record Context(Map<String,Object> client,Map<String,Map<String,Object>> products){}
}
