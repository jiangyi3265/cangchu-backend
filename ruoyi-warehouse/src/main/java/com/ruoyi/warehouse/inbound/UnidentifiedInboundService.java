package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.finance.StorageHistory;
import com.ruoyi.warehouse.inventory.*;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observed physical arrivals are separate from SKU stock until an evidenced identification. */
@Service
public class UnidentifiedInboundService {
    private final WarehouseRepository records;private final WarehouseScope scope;private final InventoryService inventory;private final InboundService inbound;private final JdbcTemplate jdbc;
    public UnidentifiedInboundService(WarehouseRepository records,WarehouseScope scope,InventoryService inventory,InboundService inbound){this.records=records;this.scope=scope;this.inventory=inventory;this.inbound=inbound;this.jdbc=records.jdbc();}
    @Transactional
    public Map<String,Object> action(String id,String action,Map<String,Object> input){
        Set<String> keys=switch(action){
            case "unknownArrive"->Set.of("requestId","_version","location","quantity","barcode","description","evidenceReference","batch","serial","expiry","reason");
            case "unknownApprove"->Set.of("requestId","_version","arrival","decision","quantity","product","batch","serial","expiry","recipient","destination","reference","reason");
            case "unknownExecute"->Set.of("requestId","_version","arrival","approval","quantity","confirmPhysical","reference","reason");
            case "unknownCancel"->Set.of("requestId","_version","arrival","approval","reason");
            default->throw WarehouseException.invalid("Unsupported unidentified arrival action");
        };
        InboundRequest.keys(input,keys);InboundRequest.version(input);String request=InboundRequest.id(input,"requestId"),reason=InboundRequest.text(input,"reason",500,true),hash=InboundRequest.hash(Map.of("id",id,"action",action,"input",input));
        records.scoped("asns",id,false);var replay=inbound.claim(request,"ASN_"+action,hash);if(replay!=null)return replay;
        AsnLateSupplement.lockParent(records,id);var before=records.scoped("asns",id,true);InboundService.nativeAsn(before);InboundService.current(before,input);
        if(!Set.of("NEW","PARTIALLY_RECEIVED","RECEIVED").contains(before.get("status")))throw WarehouseException.conflict("Use an open ASN for unidentified arrivals / 请在未结案入库单登记未识别到货");
        var asn=InboundService.business(before);String event=UUID.randomUUID().toString(),arrival,approvalId=null,custody=null,reference="";long quantity;Map<String,Object> result;
        if("unknownArrive".equals(action)){
            quantity=InboundRequest.quantity(input.get("quantity"));if(AsnUnidentified.count(jdbc,id)>=250||AsnCustody.arrivedUnits(jdbc,id)+quantity>10_000_000)throw WarehouseException.conflict("Unidentified arrival limit reached / 未识别到货已达单据上限");
            String location=InboundRequest.id(input,"location"),barcode=InboundRequest.text(input,"barcode",200,false),description=InboundRequest.text(input,"description",500,true),evidence=InboundRequest.text(input,"evidenceReference",200,true);var attrs=attributes(input,quantity);
            inventory.changeUnidentified(asn,location,quantity);arrival=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO wh_asn_unidentified(id,asn_id,client_id,warehouse_id,location_id,quantity,observed_barcode,observed_description,evidence_reference,batch_number,serial_number,expiry_date,reason,actor_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrival,id,asn.get("client"),asn.get("warehouse"),location,quantity,barcode,description,evidence,attrs.get("batch"),attrs.get("serial"),attrs.get("expiry"),reason,scope.actor());
            result=Map.of("location",location,"description",description,"barcode",barcode,"evidenceReference",evidence,"quantity",quantity);StorageHistory.unidentified(jdbc,arrival);
        }else{
            arrival=InboundRequest.id(input,"arrival");var rows=jdbc.queryForList("SELECT * FROM wh_asn_unidentified WHERE id=? AND asn_id=? FOR UPDATE",arrival,id);if(rows.size()!=1)throw WarehouseException.missing();var held=rows.get(0);long remaining=AsnUnidentified.remaining(held),reserved=AsnUnidentified.reserved(jdbc,arrival);
            if(remaining<=0||reserved>remaining)throw WarehouseException.conflict("Unidentified arrival is resolved or inconsistent / 未识别到货已处理或数量不一致");
            if("unknownApprove".equals(action)){
                quantity=InboundRequest.quantity(input.get("quantity"));if(quantity>remaining-reserved)throw WarehouseException.conflict("Quantity exceeds unapproved physical custody / 超出未批准处理的实物数量");
                String decision=InboundRequest.text(input,"decision",20,true);if(!Set.of("identify","returnSupplier","scrap").contains(decision))throw WarehouseException.invalid("Choose identification, return or disposal / 请选择认领、退供或报废");
                String product=null;Long revision=null;var attrs=new LinkedHashMap<String,Object>();String recipient="",destination="";reference=InboundRequest.text(input,"reference",200,true);
                if("identify".equals(decision)){
                    if(input.containsKey("recipient")||input.containsKey("destination"))throw WarehouseException.invalid("Identification cannot specify a handover destination");
                    product=InboundRequest.id(input,"product");var candidate=withProduct(asn,product);var context=inbound.context(candidate,true);var p=context.products().get(product);revision=((Number)p.get("_version")).longValue();attrs.putAll(attributes(input,quantity));
                    for(String[] pair:List.of(new String[]{"batch","batch_number"},new String[]{"serial","serial_number"},new String[]{"expiry","expiry_date"})){String observed=WarehouseJson.text(held,pair[1]);if(!observed.isEmpty()&&!observed.equals(attrs.get(pair[0])))throw WarehouseException.conflict("Identification must retain observed tracking / 认领必须保留登记时已确认的批次、串号和效期");}
                    new InventoryMovement(event,"add",product,WarehouseJson.text(asn,"warehouse"),String.valueOf(held.get("location_id")),quantity,reason,String.valueOf(attrs.get("batch")),String.valueOf(attrs.get("serial")),String.valueOf(attrs.get("expiry")),"","").validateTracking(p);
                }else{
                    for(String field:List.of("product","batch","serial","expiry"))if(input.containsKey(field))throw WarehouseException.invalid("Physical disposal cannot assign a SKU or tracking");
                    recipient=InboundRequest.text(input,"recipient",200,true);destination=InboundRequest.text(input,"destination",1000,true);attrs.putAll(Map.of("batch","","serial","","expiry",""));
                }
                approvalId=UUID.randomUUID().toString();var evidence=new LinkedHashMap<String,Object>();evidence.put("arrival",arrival);evidence.put("asn",id);evidence.put("quantity",quantity);evidence.put("decision",decision);evidence.put("product",product);evidence.put("productRevision",revision);evidence.put("attributes",attrs);evidence.put("recipient",recipient);evidence.put("destination",destination);evidence.put("reference",reference);evidence.put("observed",Map.of("barcode",held.get("observed_barcode"),"description",held.get("observed_description"),"evidenceReference",held.get("evidence_reference"),"location",held.get("location_id")));
                jdbc.update("INSERT INTO wh_asn_identity_approval(id,arrival_id,decision,quantity,product_id,product_revision,batch_number,serial_number,expiry_date,recipient,destination,reference,reason,actor_id,evidence_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",approvalId,arrival,decision,quantity,product,revision,attrs.get("batch"),attrs.get("serial"),attrs.get("expiry"),recipient,destination,reference,reason,scope.actor(),WarehouseJson.write(evidence));result=evidence;
            }else{
                approvalId=InboundRequest.id(input,"approval");var approvals=jdbc.queryForList("SELECT * FROM wh_asn_identity_approval WHERE id=? AND arrival_id=? FOR UPDATE",approvalId,arrival);if(approvals.size()!=1)throw WarehouseException.missing();var approval=approvals.get(0);long available=AsnUnidentified.number(approval,"quantity")-AsnUnidentified.number(approval,"executed_quantity")-AsnUnidentified.number(approval,"cancelled_quantity");if(available<=0)throw WarehouseException.conflict("Approval has no unused quantity / 此批准没有未使用数量");
                if("unknownCancel".equals(action)){
                    quantity=available;jdbc.update("UPDATE wh_asn_identity_approval SET cancelled_quantity=cancelled_quantity+? WHERE id=?",quantity,approvalId);result=Map.of("cancelledQuantity",quantity);
                }else{
                    quantity=InboundRequest.quantity(input.get("quantity"));if(quantity>Math.min(remaining,available))throw WarehouseException.conflict("Execution exceeds approved remaining quantity / 执行数量超过批准余量");
                    String decision=String.valueOf(approval.get("decision")),location=String.valueOf(held.get("location_id"));result=new LinkedHashMap<>();result.put("decision",decision);result.put("quantity",quantity);result.put("approvalReference",approval.get("reference"));
                    if("identify".equals(decision)){
                        if(input.containsKey("confirmPhysical")||input.containsKey("reference"))throw WarehouseException.invalid("Identification does not record a physical handover");
                        String product=String.valueOf(approval.get("product_id"));var candidate=withProduct(asn,product);var context=inbound.context(candidate,true);var p=context.products().get(product);if(((Number)p.get("_version")).longValue()!=AsnUnidentified.number(approval,"product_revision"))throw WarehouseException.conflict("Product changed after identification approval; cancel unused approval and review / 批准后商品资料已变化，请撤销未执行批准后重新核对");
                        if(!WarehouseJson.text(approval,"serial_number").isEmpty()&&quantity!=1)throw WarehouseException.invalid("Serial identification must have one unit");
                        inventory.changeUnidentified(asn,location,-quantity);custody=UUID.randomUUID().toString();
                        jdbc.update("INSERT INTO wh_asn_custody(id,asn_id,product_id,location_id,quantity,batch_number,serial_number,expiry_date,reason,received_by,received_at) SELECT ?,asn_id,?,location_id,?,?,?,?,?,actor_id,arrived_at FROM wh_asn_unidentified WHERE id=?",custody,product,quantity,approval.get("batch_number"),approval.get("serial_number"),approval.get("expiry_date"),reason,arrival);
                        jdbc.update("INSERT INTO wh_asn_unidentified_event(id,arrival_id,approval_id,action_name,quantity,custody_id,reason,actor_id,result_json) VALUES(?,?,?,?,?,?,?,?,?)",event,arrival,approvalId,action,quantity,custody,reason,scope.actor(),"{}");
                        var stock=inventory.receiveAsnCustody(id,custody);jdbc.update("UPDATE wh_asn_custody SET layer_id=? WHERE id=?",stock.get("layerId"),custody);asn=candidate;
                        jdbc.update("UPDATE wh_asn_unidentified SET mapped_quantity=mapped_quantity+? WHERE id=?",quantity,arrival);result.put("product",product);result.put("sku",p.get("sku"));result.put("custody",custody);result.put("stock",stock);
                    }else{
                        if(!Boolean.TRUE.equals(input.get("confirmPhysical")))throw WarehouseException.invalid("Confirm physical handover or disposal / 请确认已实际交接或完成报废处置");reference=InboundRequest.text(input,"reference",200,true);
                        inventory.changeUnidentified(asn,location,-quantity);String column="returnSupplier".equals(decision)?"returned_quantity":"scrapped_quantity";jdbc.update("UPDATE wh_asn_unidentified SET "+column+"="+column+"+? WHERE id=?",quantity,arrival);result.put("recipient",approval.get("recipient"));result.put("destination",approval.get("destination"));result.put("reference",reference);
                    }
                    jdbc.update("UPDATE wh_asn_identity_approval SET executed_quantity=executed_quantity+? WHERE id=?",quantity,approvalId);StorageHistory.unidentified(jdbc,arrival);
                }
            }
        }
        if(custody==null)jdbc.update("INSERT INTO wh_asn_unidentified_event(id,arrival_id,approval_id,action_name,quantity,reason,reference,actor_id,result_json) VALUES(?,?,?,?,?,?,?,?,?)",event,arrival,approvalId,action,quantity,reason,reference,scope.actor(),WarehouseJson.write(result));else jdbc.update("UPDATE wh_asn_unidentified_event SET result_json=? WHERE id=?",WarehouseJson.write(result),event);
        AsnUnidentified.capacity(jdbc,id);AsnCustody.requireCompletionCapacity(jdbc,id,asn);asn.put("custodyCount",AsnCustody.count(jdbc,id));asn.put("unidentifiedPending",AsnUnidentified.pending(jdbc,id));asn.put("custodyPending",jdbc.queryForList("SELECT quantity-disposed_quantity FROM wh_asn_custody WHERE asn_id=? FOR UPDATE",Long.class,id).stream().mapToLong(Long::longValue).sum()+AsnUnidentified.pending(jdbc,id));
        var saved=inbound.write(id,before,asn);records.audit("ASN_"+action.toUpperCase(Locale.ROOT),"asns",id,before,Map.of("asn",saved,"arrival",arrival,"event",event,"reason",reason,"result",result));return inbound.complete(request,hash,inbound.view(saved));
    }
    private static Map<String,Object> withProduct(Map<String,Object> asn,String product){var result=WarehouseJson.copy(asn);var extras=new LinkedHashSet<Object>((List<?>)asn.getOrDefault("exceptionProducts",List.of()));if(!InboundService.expected(asn).containsKey(product))extras.add(product);result.put("exceptionProducts",new ArrayList<>(extras));return result;}
    private static Map<String,Object> attributes(Map<String,Object> input,long quantity){var result=new LinkedHashMap<String,Object>();for(String key:List.of("batch","serial","expiry"))result.put(key,InboundRequest.text(input,key,"expiry".equals(key)?10:191,false));if(!result.get("serial").equals("")&&quantity!=1)throw WarehouseException.invalid("Serial arrivals require one unit / 串号到货每次只能登记 1 件");if(!result.get("expiry").equals(""))InboundRequest.date(String.valueOf(result.get("expiry")));return result;}
}
