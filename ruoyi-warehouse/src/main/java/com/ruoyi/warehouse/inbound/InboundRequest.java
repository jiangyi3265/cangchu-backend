package com.ruoyi.warehouse.inbound;

import com.ruoyi.warehouse.inventory.InventoryMovement;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

final class InboundRequest {
    static final Set<String> DELIVERY_TYPES = Set.of("FCL Container 20", "FCL Container 40", "FCL Container 40 HC", "FCL Container 45", "FCL Container 45 HC", "LCL Pallet", "LCL Boxes");
    private InboundRequest() { }
    static Map<String,Object> forecast(Map<String,Object> in, boolean update) {
        keys(in, update ? Set.of("requestId","_version","client","warehouse","reference","expectedDate","deliveryType","notes","lines","supplierId") : Set.of("requestId","client","warehouse","reference","expectedDate","deliveryType","notes","lines","supplierId"));
        id(in,"requestId"); if(update) version(in);
        var result = new LinkedHashMap<String,Object>();
        result.put("client",id(in,"client")); result.put("warehouse",id(in,"warehouse"));
        result.put("reference",text(in,"reference",191,true)); result.put("notes",text(in,"notes",5000,false));
        String date=text(in,"expectedDate",10,false); if(!date.isEmpty()) date(date); result.put("expectedDate",date);
        String type=text(in,"deliveryType",40,true); if(!DELIVERY_TYPES.contains(type))throw WarehouseException.invalid("Unsupported delivery type / 到货类型无效"); result.put("deliveryType",type);
        var lines=new ArrayList<Map<String,Object>>(); var seen=new HashSet<String>(); long total=0;
        for(var row:rows(in.get("lines"))) {
            keys(row,Set.of("product","quantity")); String product=id(row,"product");
            if(!seen.add(product))throw WarehouseException.invalid("Combine duplicate forecast products / 请合并预报中的重复商品");
            long quantity=quantity(row.get("quantity")); total+=quantity;
            if(total>10_000_000)throw WarehouseException.invalid("ASN quantity exceeds limit");
            lines.add(new LinkedHashMap<>(Map.of("product",product,"quantity",quantity)));
        }
        if(in.containsKey("supplierId"))result.put("supplierId",text(in,"supplierId",64,false));
        result.put("lines",lines); return result;
    }
    static List<InventoryMovement> receipt(Map<String,Object> in,Map<String,Object> asn) {
        keys(in,Set.of("requestId","_version","lines")); String request=id(in,"requestId"); version(in);
        var result=new ArrayList<InventoryMovement>();
        for(var row:rows(in.get("lines"))) {
            keys(row,Set.of("product","location","quantity","batch","serial","expiry"));
            var value=new LinkedHashMap<>(row); value.put("requestId",request); value.put("action","add"); value.put("warehouse",asn.get("warehouse")); value.put("reason","ASN receiving / 入库收货");
            result.add(InventoryMovement.parse(value));
        }
        return result;
    }
    static List<Map<String,Object>> rows(Object input) {
        if(!(input instanceof List<?> list)||list.isEmpty()||list.size()>200)throw WarehouseException.invalid("Provide 1 to 200 lines / 请填写 1 至 200 行明细");
        var out=new ArrayList<Map<String,Object>>(); for(Object value:list){if(!(value instanceof Map<?,?>))throw WarehouseException.invalid("Invalid line");out.add(WarehouseJson.map(WarehouseJson.write(value)));}return out;
    }
    static void keys(Map<String,Object> in,Set<String> allowed) {
        if(in==null||WarehouseJson.write(in).length()>262144)throw WarehouseException.invalid("Inbound input missing or too large");
        for(String key:in.keySet())if(!allowed.contains(key))throw WarehouseException.invalid("Unknown or protected inbound field: "+key);
    }
    static String text(Map<String,Object> in,String key,int max,boolean required){Object raw=in.get(key);if(raw!=null&&!(raw instanceof String))throw WarehouseException.invalid(key+" must be text");String s=raw==null?"":((String)raw).strip();if(s.length()>max||s.indexOf('\0')>=0||required&&s.isEmpty())throw WarehouseException.invalid(key+" is required or exceeds limit");return s;}
    static String id(Map<String,Object> in,String key){String s=text(in,key,64,true);if(!s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw WarehouseException.invalid("Invalid "+key);return s;}
    static long version(Map<String,Object> in){return WarehouseJson.integer(in.get("_version"),"_version",1,Long.MAX_VALUE);}
    static long quantity(Object v){return WarehouseJson.integer(v,"quantity",1,10_000_000);}
    static BigDecimal decimal(Object raw,String name){try{if(raw==null||raw.toString().isBlank()||raw.toString().length()>60)throw new NumberFormatException();var n=new BigDecimal(raw.toString());if(n.signum()<0||n.scale()>9||n.precision()>24||(long)n.precision()-n.scale()>22)throw new NumberFormatException();return n;}catch(Exception e){throw WarehouseException.invalid(name+" needs an explicit nonnegative rate / 请配置有效价格，免费请填 0");}}
    static void date(String value){try{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||LocalDate.parse(value).getYear()<1)throw new IllegalArgumentException();}catch(Exception e){throw WarehouseException.invalid("Invalid expected date / 预计到货日期无效");}}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(canonical(value)).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static Object canonical(Object value){if(value instanceof Map<?,?> m){var out=new TreeMap<String,Object>();m.forEach((k,v)->out.put(String.valueOf(k),canonical(v)));return out;}if(value instanceof List<?> list)return list.stream().map(InboundRequest::canonical).toList();return value;}
}
