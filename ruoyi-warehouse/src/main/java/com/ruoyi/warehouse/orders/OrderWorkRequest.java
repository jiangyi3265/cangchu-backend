package com.ruoyi.warehouse.orders;

import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.util.*;

final class OrderWorkRequest {
    private OrderWorkRequest() { }
    static Map<String,Object> parse(Map<String,Object> input) {
        if(input==null||WarehouseJson.write(input).length()>131072)throw WarehouseException.invalid("Work request is missing or too large");
        String action=OrderRequest.text(input,"action",16,true);
        var fields=new HashSet<>(Set.of("requestId","_version","action"));
        switch(action){
            case "scan"->fields.addAll(Set.of("allocationId","barcode","serial"));
            case "unscan"->fields.addAll(Set.of("scanId","reason"));
            case "pack"->fields.addAll(Set.of("packageCode","weightKg","lengthCm","widthCm","heightCm","notes","lines"));
            case "unpack"->fields.addAll(Set.of("packageId","reason"));
            case "reopenpick"->fields.add("reason");
            default->throw WarehouseException.invalid("Unsupported warehouse work action");
        }
        OrderRequest.keys(input,fields);OrderRequest.id(input,"requestId");WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE);
        var result=new LinkedHashMap<String,Object>();result.put("action",action);result.put("_version",input.get("_version"));
        if("scan".equals(action)){result.put("allocationId",OrderRequest.id(input,"allocationId"));result.put("barcode",OrderRequest.text(input,"barcode",191,true));result.put("serial",OrderRequest.text(input,"serial",191,false));}
        else if("pack".equals(action)){
            result.put("packageCode",OrderRequest.text(input,"packageCode",100,true));result.put("notes",OrderRequest.text(input,"notes",500,false));
            result.put("weightKg",measurement(input.get("weightKg"),"weightKg",3,100000));
            for(String key:List.of("lengthCm","widthCm","heightCm"))result.put(key,measurement(input.get(key),key,2,1000));
            if(!(input.get("lines") instanceof List<?> rows)||rows.isEmpty()||rows.size()>200)throw WarehouseException.invalid("A package needs 1 to 200 allocation lines / 每箱需填写 1 至 200 行商品");
            var lines=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
            for(Object raw:rows){if(!(raw instanceof Map<?,?>))throw WarehouseException.invalid("Invalid package line");var row=WarehouseJson.map(WarehouseJson.write(raw));OrderRequest.keys(row,Set.of("allocationId","quantity"));String id=OrderRequest.id(row,"allocationId");if(!seen.add(id))throw WarehouseException.invalid("Combine repeated package lines / 请合并同一库存分配行");lines.add(Map.of("allocationId",id,"quantity",WarehouseJson.integer(row.get("quantity"),"quantity",1,10000000)));}result.put("lines",lines);
        }else{result.put("reason",OrderRequest.text(input,"reason",500,true));if("unscan".equals(action))result.put("scanId",OrderRequest.id(input,"scanId"));if("unpack".equals(action))result.put("packageId",OrderRequest.id(input,"packageId"));}
        return result;
    }
    private static BigDecimal measurement(Object raw,String key,int scale,int maximum){try{if(raw==null||raw.toString().length()>40)throw new NumberFormatException();var n=new BigDecimal(raw.toString());if(n.signum()<=0||n.scale()>scale||n.compareTo(BigDecimal.valueOf(maximum))>0)throw new NumberFormatException();return n;}catch(Exception e){throw WarehouseException.invalid(key+" must be a positive measured value within limits / 请填写有效的实测重量和尺寸");}}
}
