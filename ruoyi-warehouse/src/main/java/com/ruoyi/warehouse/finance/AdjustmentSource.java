package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.support.*;
import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.ruoyi.warehouse.finance.AdjustmentSupport.*;

/** Resolves recorded charge evidence. Never consults today's prices or changes physical stock. */
final class AdjustmentSource {
    private final WarehouseRepository records;private final FinanceWorkflowService workflow;private final JdbcTemplate jdbc;
    AdjustmentSource(WarehouseRepository records,FinanceWorkflowService workflow){this.records=records;this.workflow=workflow;jdbc=records.jdbc();}
    record State(Map<String,Object> charge,Map<String,Object> invoice,Map<String,Object> invoiceLine,List<Map<String,Object>> components,List<Map<String,Object>> effects,Map<String,BigDecimal> reduced,BigDecimal remainingRaw,BigDecimal remainingNet,String sourceHash) { }
    State load(String id){
        var charge=records.get("charges",id,true);workflow.assertAggregate("charges",charge);money(charge.get("amount"));
        var proofs=jdbc.queryForList("SELECT evidence_json FROM wh_finance_source WHERE charge_id=?",String.class,id);Map<String,Object> proof;
        if(proofs.size()==1){proof=WarehouseJson.map(proofs.get(0));Map<String,Object> quote=proof.get("quote") instanceof Map<?,?>?map(proof.get("quote")):proof.get("entry") instanceof Map<?,?>?map(proof.get("entry")):proof.get("input") instanceof Map<?,?>?map(proof.get("input")):proof;
            if(!quote.containsKey("amount")||money(quote.get("amount")).compareTo(money(charge.get("amount")))!=0)throw WarehouseException.conflict("Original fee evidence differs from the posted amount / 原收费证据与金额不一致");
            Object lines=quote.getOrDefault("lines",quote.get("breakdown"));if(lines!=null&&!hash(lines).equals(hash(charge.get("breakdown"))))throw WarehouseException.conflict("Original fee components changed / 原收费明细已改变");
        }else{
            var order=jdbc.queryForList("SELECT order_id,source_snapshot,quote_json FROM wh_order_billing WHERE charge_id=?",id);if(order.size()!=1)throw WarehouseException.conflict("Original fee evidence is missing / 缺少原收费证据");
            var row=order.get(0);var quote=WarehouseJson.map(String.valueOf(row.get("quote_json")));if(money(quote.get("amount")).compareTo(money(charge.get("amount")))!=0||!hash(quote.get("lines")).equals(hash(charge.get("breakdown"))))throw WarehouseException.conflict("Original order fee differs / 原订单收费证据不一致");
            proof=Map.of("order",row.get("order_id"),"source",WarehouseJson.map(String.valueOf(row.get("source_snapshot"))),"quote",quote);
        }
        var identity=new LinkedHashMap<String,Object>();for(String key:List.of("id","client","warehouse","warehouseIds","orderIds","asnId","returnId","source","amount","currency","breakdown"))if(charge.containsKey(key))identity.put(key,charge.get(key));String sourceHash=hash(List.of(identity,proof));
        var components=components(charge,proof);var effects=new ArrayList<Map<String,Object>>();var reduced=new TreeMap<String,BigDecimal>();
        for(var row:jdbc.queryForList("SELECT * FROM wh_finance_adjustment_effect WHERE charge_id=? ORDER BY sequence_no FOR UPDATE",id)){
            if(effects.size()>=10000)throw WarehouseException.conflict("Adjustment history requires a separate reconciliation / 调整历史需单独对账");
            var effect=WarehouseJson.map(String.valueOf(row.get("effect_json")));if(!hash(effect).equals(row.get("effect_hash"))||!sourceHash.equals(effect.get("sourceHash"))||((Number)row.get("sequence_no")).longValue()!=effects.size()+1)throw WarehouseException.conflict("Adjustment evidence failed reconciliation / 调整证据校验失败");
            BigDecimal before=remainingRaw(components,reduced);if(before.compareTo(rawDecimal(effect.get("beforeRaw")))!=0)throw WarehouseException.conflict("Adjustment balance chain changed / 调整余额记录不连续");
            for(var d:WarehouseJson.maps(effect.get("decisions")))if("reduce".equals(d.get("decision")))reduced.merge(text(d,"component"),number(d.get("quantity")),BigDecimal::add);
            BigDecimal after=remainingRaw(components,reduced);if(after.compareTo(rawDecimal(effect.get("remainingRaw")))!=0||money(effect.get("net")).compareTo(money(before).subtract(money(after)))!=0||money(row.get("net")).compareTo(money(effect.get("net")))!=0||money(row.get("tax")).compareTo(money(effect.get("tax")))!=0)throw WarehouseException.conflict("Adjustment amounts do not reconcile / 调整金额不能对平");
            effects.add(effect);
        }
        BigDecimal remaining=remainingRaw(components,reduced),net=money(remaining);Map<String,Object> invoice=null,line=null;
        if("INVOICED".equals(charge.get("status"))){
            invoice=records.get("invoices",text(charge,"invoice"),true);workflow.assertAggregate("invoices",invoice);if(!Set.of("DRAFT","CONFIRMED").contains(invoice.get("status"))||!Objects.equals(charge.get("client"),invoice.get("client"))||!Objects.equals(charge.get("currency"),invoice.get("currency")))throw WarehouseException.conflict("Invoice ownership or currency changed / 账单归属或币种已改变");
            var selected=WarehouseJson.maps(invoice.get("lines")).stream().filter(l->id.equals(l.get("id"))).toList();if(selected.size()!=1)throw WarehouseException.conflict("Invoice line is not unique / 账单原行不唯一");line=selected.get(0);
            var frozen=jdbc.queryForList("SELECT snapshot_json FROM wh_finance_invoice_line WHERE invoice_id=? AND charge_id=?",String.class,invoice.get("id"),id);if(frozen.size()!=1||!hash(map(WarehouseJson.map(frozen.get(0)).get("line"))).equals(hash(line)))throw WarehouseException.conflict("Invoice line snapshot changed / 原账单行快照已改变");
            BigDecimal totalNet=BigDecimal.ZERO,totalTax=BigDecimal.ZERO;
            for(var item:WarehouseJson.maps(invoice.get("lines"))){BigDecimal rate=number(item.get("vatRate"));if(rate.compareTo(new BigDecimal("100"))>0||tax(FinanceInput.money(item.get("amount")),rate).compareTo(FinanceInput.money(item.get("tax")))!=0)throw WarehouseException.conflict("Original tax cannot be verified / 无法核对原税额");totalNet=totalNet.add(FinanceInput.money(item.get("amount")));totalTax=totalTax.add(FinanceInput.money(item.get("tax")));}
            if(totalNet.compareTo(FinanceInput.money(invoice.get("net")))!=0||totalTax.compareTo(FinanceInput.money(invoice.get("tax")))!=0||totalNet.add(totalTax).compareTo(FinanceInput.money(invoice.get("amount")))!=0)throw WarehouseException.conflict("Original invoice totals do not reconcile / 原账单合计不一致");
            BigDecimal credited=BigDecimal.ZERO,creditedTax=BigDecimal.ZERO;for(var effect:effects)if("CREDIT_NOTE".equals(effect.get("route"))&&Objects.equals(invoice.get("id"),effect.get("invoiceId"))){credited=credited.add(money(effect.get("net")));creditedTax=creditedTax.add(money(effect.get("tax")));}
            if(money(line.get("amount")).subtract(credited).compareTo(net)!=0||money(line.get("tax")).subtract(creditedTax).compareTo(tax(net,line.get("vatRate")))!=0)throw WarehouseException.conflict("Invoice credit balance does not reconcile / 原账单与贷项余额不一致");
        }else if(!"UNCONFIRMED".equals(charge.get("status"))||money(charge.getOrDefault("billableAmount",charge.get("amount"))).compareTo(net)!=0)throw WarehouseException.conflict("Unbilled charge balance changed / 未开票费用余额已改变");
        return new State(charge,invoice,line,components,effects,reduced,remaining,net,sourceHash);
    }
    static BigDecimal remainingRaw(List<Map<String,Object>> components,Map<String,BigDecimal> reduced){
        var ids=new HashSet<String>();BigDecimal total=BigDecimal.ZERO;for(var component:components){String id=text(component,"id");ids.add(id);BigDecimal q=number(component.get("quantity")).subtract(reduced.getOrDefault(id,BigDecimal.ZERO));if(q.signum()<0)throw WarehouseException.conflict("Reduction exceeds the original component quantity / 核减超出原收费数量");total=total.add(q.multiply(number(component.get("rate"))).setScale(9,RoundingMode.DOWN));}
        if(!ids.containsAll(reduced.keySet()))throw WarehouseException.conflict("Unknown original component / 缺少原收费项目");return total;
    }
}
