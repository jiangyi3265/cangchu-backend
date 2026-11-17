package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.inventory.*;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Staging, inspection and physical disposition, with the ASN as concurrency owner. */
@Service
public class AsnCustodyService {
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final InventoryService inventory;
    private final InboundService inbound;
    private final JdbcTemplate jdbc;
    public AsnCustodyService(WarehouseRepository records,WarehouseScope scope,InventoryService inventory,InboundService inbound){this.records=records;this.scope=scope;this.inventory=inventory;this.inbound=inbound;jdbc=records.jdbc();}

    @Transactional
    public Map<String,Object> action(String id,String action,Map<String,Object> input){
        Set<String> keys=switch(action){
            case "stage" -> Set.of("requestId","_version","product","location","quantity","batch","serial","expiry","reason");
            case "inspect" -> Set.of("requestId","_version","task","result","reason");
            case "approveOverage" -> Set.of("requestId","_version","task","reason");
            case "putaway" -> Set.of("requestId","_version","task","quantity","location","reason");
            case "scrap" -> Set.of("requestId","_version","task","quantity","reason");
            case "approveReturn" -> Set.of("requestId","_version","task","quantity","recipient","destination","reference","reason");
            case "cancelReturnApproval" -> Set.of("requestId","_version","task","approval","reason");
            case "returnSupplier" -> Set.of("requestId","_version","task","approval","quantity","handoverReference","confirmHandover","reason");
            default -> throw WarehouseException.invalid("Unsupported inbound custody action / 不支持的入库保管操作");
        };
        InboundRequest.keys(input,keys);InboundRequest.version(input);
        String request=InboundRequest.id(input,"requestId"),reason=InboundRequest.text(input,"reason",500,true),hash=InboundRequest.hash(Map.of("id",id,"action",action,"input",input));
        // Recheck data scope even on a successful replay, before consulting its journal.
        records.scoped("asns",id,false);
        var replay=inbound.claim(request,"ASN_CUSTODY_"+action,hash);if(replay!=null)return replay;
        AsnLateSupplement.lockParent(records,id);var before=records.scoped("asns",id,true);InboundService.nativeAsn(before);InboundService.current(before,input);
        if(!Set.of("NEW","PARTIALLY_RECEIVED","RECEIVED").contains(before.get("status")))throw WarehouseException.conflict("ASN is closed / 入库单已结束");
        var asn=InboundService.business(before);var context=inbound.context(asn,Set.of("stage","putaway").contains(action));
        String event=UUID.randomUUID().toString(),task;long quantity=0;String location=null,returnApproval=null;Map<String,Object> result;
        if("stage".equals(action)){
            if(AsnCustody.count(jdbc,id)>=2000)throw WarehouseException.conflict("ASN custody task limit reached / 入库保管任务已达上限");
            var body=new LinkedHashMap<>(input);body.keySet().removeAll(Set.of("_version","reason"));body.put("reason",reason);body.put("action","add");body.put("warehouse",asn.get("warehouse"));
            var movement=InventoryMovement.parse(body);
            if(!context.products().containsKey(movement.product()))throw WarehouseException.invalid("Product is not on this ASN / 商品不在预报内，请先核对商品身份");
            long total=AsnCustody.arrivedUnits(jdbc,id);
            if(total+movement.quantity()>10_000_000)throw WarehouseException.conflict("ASN custody quantity limit reached");
            task=UUID.randomUUID().toString();quantity=movement.quantity();location=movement.location();
            jdbc.update("INSERT INTO wh_asn_custody(id,asn_id,product_id,location_id,quantity,batch_number,serial_number,expiry_date,reason,received_by) VALUES(?,?,?,?,?,?,?,?,?,?)",task,id,movement.product(),location,quantity,movement.batch(),movement.serial(),movement.expiry(),reason,scope.actor());
            result=inventory.receiveAsnCustody(id,task);
            jdbc.update("UPDATE wh_asn_custody SET layer_id=? WHERE id=? AND layer_id IS NULL",result.get("layerId"),task);
        }else{
            task=InboundRequest.id(input,"task");var rows=jdbc.queryForList("SELECT * FROM wh_asn_custody WHERE id=? AND asn_id=? FOR UPDATE",task,id);
            if(rows.size()!=1)throw WarehouseException.missing();var held=rows.get(0);
            long remaining=number(held,"quantity")-number(held,"disposed_quantity");if(remaining==0)throw WarehouseException.conflict("Custody task is already complete / 此保管任务已完成");
            long reserved=AsnSupplierReturn.reserved(jdbc,task);
            if(reserved>remaining)throw WarehouseException.conflict("Supplier return reservations exceed custody / 退供批准数量与保管库存不一致");
            if("inspect".equals(action)){
                String inspection=InboundRequest.text(input,"result",10,true);
                if(!Set.of("PASS","FAIL").contains(inspection))throw WarehouseException.invalid("Choose pass or fail / 请选择合格或不合格");
                if(number(held,"disposed_quantity")-number(held,"returned_quantity")>0)throw WarehouseException.conflict("Inspection is fixed after putaway or scrap starts / 已开始上架或报废，不能改写检验结论");
                if(reserved>0)throw WarehouseException.conflict("Cancel unused supplier return approval before reinspection / 请先撤销未交接的退供批准，再修改检验结论");
                jdbc.update("UPDATE wh_asn_custody SET inspection=?,approved_extra=0 WHERE id=?",inspection,task);result=Map.of("inspection",inspection);
            }else if("approveOverage".equals(action)){
                if(!"PASS".equals(held.get("inspection")))throw WarehouseException.conflict("Inspect goods before approving overage / 请先检验合格再批准超收");
                if(number(held,"approved_extra")>=remaining)throw WarehouseException.conflict("Remaining quantity is already approved / 剩余数量已获批准");
                quantity=remaining;jdbc.update("UPDATE wh_asn_custody SET approved_extra=? WHERE id=?",remaining,task);result=Map.of("approvedExtra",remaining);
            }else if("approveReturn".equals(action)){
                quantity=InboundRequest.quantity(input.get("quantity"));if(quantity>remaining-reserved)throw WarehouseException.conflict("Quantity exceeds unreserved custody / 数量超过尚未批准退供的保管实物");
                String recipient=InboundRequest.text(input,"recipient",200,true),destination=InboundRequest.text(input,"destination",1000,true),reference=InboundRequest.text(input,"reference",200,true);returnApproval=UUID.randomUUID().toString();
                var evidence=new LinkedHashMap<String,Object>();evidence.put("asn",id);evidence.put("task",task);evidence.put("client",asn.get("client"));evidence.put("warehouse",asn.get("warehouse"));
                for(String field:List.of("product_id","location_id","layer_id","batch_number","serial_number","expiry_date","inspection"))evidence.put(field,held.get(field));
                evidence.put("quantity",quantity);evidence.put("remainingAtApproval",remaining);evidence.put("recipient",recipient);evidence.put("destination",destination);evidence.put("reference",reference);
                jdbc.update("INSERT INTO wh_asn_return_approval(id,custody_id,quantity,recipient,destination,reference,reason,actor_id,evidence_json) VALUES(?,?,?,?,?,?,?,?,?)",returnApproval,task,quantity,recipient,destination,reference,reason,scope.actor(),WarehouseJson.write(evidence));
                result=Map.of("approval",returnApproval,"quantity",quantity,"recipient",recipient,"destination",destination,"reference",reference);
            }else if("cancelReturnApproval".equals(action)){
                returnApproval=InboundRequest.id(input,"approval");var approval=AsnSupplierReturn.approval(jdbc,task,returnApproval);quantity=AsnSupplierReturn.remaining(approval);
                if(quantity<=0)throw WarehouseException.conflict("Supplier return approval has no unused quantity / 退供批准没有未使用的数量");
                jdbc.update("UPDATE wh_asn_return_approval SET cancelled_quantity=cancelled_quantity+? WHERE id=?",quantity,returnApproval);result=Map.of("approval",returnApproval,"cancelledQuantity",quantity);
            }else{
                quantity=InboundRequest.quantity(input.get("quantity"));if(quantity>remaining)throw WarehouseException.conflict("Quantity exceeds remaining custody / 数量超过待处理实物");
                boolean putaway="putaway".equals(action),supplierReturn="returnSupplier".equals(action);long extra=0;String handoverReference="";Map<String,Object> approval=null;
                if(supplierReturn){
                    if(!Boolean.TRUE.equals(input.get("confirmHandover")))throw WarehouseException.invalid("Confirm goods were physically handed over / 请确认货物已经实际交接");
                    returnApproval=InboundRequest.id(input,"approval");handoverReference=InboundRequest.text(input,"handoverReference",200,true);approval=AsnSupplierReturn.approval(jdbc,task,returnApproval);
                    if(quantity>AsnSupplierReturn.remaining(approval))throw WarehouseException.conflict("Handover exceeds unused approval / 交接数量超过未使用的批准数量");
                }else if(quantity>remaining-reserved)throw WarehouseException.conflict("Stock is reserved for an approved supplier return / 此数量已批准退供，请先交接或撤销批准");
                if(putaway){
                    location=InboundRequest.id(input,"location");String product=String.valueOf(held.get("product_id"));
                    extra=Math.max(0,quantity-Math.max(0,InboundService.expected(asn).get(product)-InboundService.received(asn).getOrDefault(product,0L)));
                    if(extra>number(held,"approved_extra"))throw WarehouseException.conflict("Overage requires approval before putaway / 超出预报的数量需先批准，再上架");
                }
                jdbc.update("INSERT INTO wh_asn_custody_event(id,custody_id,action_name,quantity,location_id,reason,actor_id,return_approval_id,handover_reference) VALUES(?,?,?,?,?,?,?,?,?)",event,task,action,quantity,location,reason,scope.actor(),returnApproval,handoverReference);
                result=new LinkedHashMap<>(inventory.disposeAsnCustody(id,event));
                if(putaway)inbound.acceptCustody(id,task,event,before,asn,context,held,quantity,location,result);
                if(supplierReturn){
                    if(jdbc.update("UPDATE wh_asn_return_approval SET handed_quantity=handed_quantity+? WHERE id=? AND quantity-handed_quantity-cancelled_quantity>=?",quantity,returnApproval,quantity)!=1)throw WarehouseException.conflict("Supplier return approval changed");
                    result.put("approval",returnApproval);result.put("recipient",approval.get("recipient"));result.put("destination",approval.get("destination"));result.put("approvalReference",approval.get("reference"));result.put("handoverReference",handoverReference);
                }
                jdbc.update("UPDATE wh_asn_custody SET disposed_quantity=disposed_quantity+?,accepted_quantity=accepted_quantity+?,returned_quantity=returned_quantity+?,approved_extra=? WHERE id=?",quantity,putaway?quantity:0,supplierReturn?quantity:0,Math.min(number(held,"approved_extra")-extra,remaining-quantity),task);
            }
        }
        if(Set.of("stage","inspect","approveOverage","approveReturn","cancelReturnApproval").contains(action))jdbc.update("INSERT INTO wh_asn_custody_event(id,custody_id,action_name,quantity,location_id,reason,actor_id,result_json,return_approval_id) VALUES(?,?,?,?,?,?,?,?,?)",event,task,action,quantity,location,reason,scope.actor(),WarehouseJson.write(result),returnApproval);
        else jdbc.update("UPDATE wh_asn_custody_event SET result_json=? WHERE id=? AND result_json IS NULL",WarehouseJson.write(result),event);
        AsnCustody.requireCompletionCapacity(jdbc,id,asn);
        asn.put("custodyCount",AsnCustody.count(jdbc,id));
        asn.put("custodyPending",jdbc.queryForList("SELECT quantity-disposed_quantity FROM wh_asn_custody WHERE asn_id=? FOR UPDATE",Long.class,id).stream().mapToLong(Long::longValue).sum()+AsnUnidentified.pending(jdbc,id));
        var saved=inbound.write(id,before,asn);records.audit("ASN_CUSTODY_"+action.toUpperCase(Locale.ROOT),"asns",id,before,Map.of("asn",saved,"task",task,"event",event,"reason",reason,"result",result));
        return inbound.complete(request,hash,inbound.view(saved));
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
