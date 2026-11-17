package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.support.*;
import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

final class AdjustmentSupport {
    private AdjustmentSupport() { }
    static String hash(Object value){return FinanceInput.hash(normal(value));}
    private static Object normal(Object value){
        if(value instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();
        if(value instanceof Map<?,?> m){var result=new TreeMap<String,Object>();m.forEach((k,v)->result.put(String.valueOf(k),normal(v)));return result;}
        if(value instanceof List<?> l)return l.stream().map(AdjustmentSupport::normal).toList();return value;
    }
    static Map<String,Object> map(Object value){if(!(value instanceof Map<?,?>))throw WarehouseException.invalid("Expected an object / 需要对象格式的数据");return WarehouseJson.map(WarehouseJson.write(value));}
    static BigDecimal number(Object value){return FinanceInput.number(value,"adjustment value",false);}
    static BigDecimal rawDecimal(Object value){try{if(!(value instanceof String||value instanceof Number))throw new IllegalArgumentException();BigDecimal n=new BigDecimal(value.toString());if(n.signum()<0||n.scale()>9||(long)n.precision()-n.scale()>22||n.stripTrailingZeros().precision()>31)throw new IllegalArgumentException();return n;}catch(Exception e){throw WarehouseException.conflict("Invalid original precision or balance / 原始精度或余额无法核对");}}
    static BigDecimal money(Object value){return FinanceInput.money(rawDecimal(value).setScale(2,RoundingMode.HALF_EVEN));}
    static BigDecimal tax(BigDecimal amount,Object rate){return amount.multiply(number(rate)).movePointLeft(2).setScale(2,RoundingMode.HALF_EVEN);}
    static BigDecimal raw(Object value){try{var n=new BigDecimal(new BigInteger(value.toString()),9);if(n.signum()<0||n.precision()>40)throw new IllegalArgumentException();return n;}catch(Exception e){throw WarehouseException.conflict("Original raw fee is not verifiable / 无法核对原始费用精度");}}
    static String text(Map<String,Object> row,String field){return WarehouseJson.text(row,field);}
    static void version(Map<String,Object> row,Map<String,Object> input){if(((Number)row.get("_version")).longValue()!=WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE))throw WarehouseException.conflict("Adjustment changed; reload / 调整记录已改变，请刷新");}
    static void unlocked(JdbcTemplate jdbc,String charge,String allowed){
        for(String approval:jdbc.queryForList("SELECT approval_id FROM wh_finance_adjustment_lock WHERE charge_id=? FOR UPDATE",String.class,charge))if(!approval.equals(allowed))throw WarehouseException.conflict("An approved adjustment reserves this fee; execute or revoke it first / 此费用已有待执行调整，请先执行或撤销批准");
    }
    static void invoiceUnlocked(JdbcTemplate jdbc,Map<String,Object> invoice,String allowed){for(var line:WarehouseJson.maps(invoice.get("lines")))unlocked(jdbc,text(line,"id"),allowed);}
    static List<Map<String,Object>> components(Map<String,Object> charge,Map<String,Object> evidence){
        BigDecimal amount=money(charge.get("amount"));List<Map<String,Object>> lines=WarehouseJson.maps(charge.get("breakdown"));
        boolean exact=!lines.isEmpty()&&lines.stream().allMatch(l->l.containsKey("raw")&&l.containsKey("quantity")&&l.containsKey("rate"));var result=new ArrayList<Map<String,Object>>();BigDecimal total=BigDecimal.ZERO;
        if(exact)for(int i=0;i<lines.size();i++){
            var line=lines.get(i);BigDecimal quantity=number(line.get("quantity")),rate=number(line.get("rate")),value=raw(line.get("raw"));
            if(quantity.multiply(rate).setScale(9,RoundingMode.DOWN).compareTo(value)!=0)throw WarehouseException.conflict("Original component quantity and price do not reconcile / 原收费数量与单价不一致");
            String source=text(line,"source"),label=text(line,"description");int scale=label.equals("ASN labour hours")?3:source.startsWith("asn:")?0:9;
            var component=new LinkedHashMap<String,Object>();component.put("id","line-"+i);component.put("description",label);component.put("quantity",quantity);component.put("rate",rate);component.put("raw",value);component.put("quantityScale",scale);component.put("basis","SOURCE_QUANTITY");result.add(component);total=total.add(value);
        }
        if(exact&&total.setScale(2,RoundingMode.HALF_EVEN).compareTo(amount)!=0)throw WarehouseException.conflict("Original components do not reconcile to the posted fee / 原收费明细与总额不一致");
        if(!exact){var component=new LinkedHashMap<String,Object>();component.put("id","amount");component.put("description","Recorded net amount / 已记录税前金额");component.put("quantity",amount);component.put("rate",BigDecimal.ONE);component.put("raw",amount);component.put("quantityScale",2);component.put("basis","NET_AMOUNT");result.add(component);}
        return result;
    }
}
