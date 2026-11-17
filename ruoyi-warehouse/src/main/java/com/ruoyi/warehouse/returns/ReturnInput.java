package com.ruoyi.warehouse.returns;

import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class ReturnInput {
    private ReturnInput() { }
    static void keys(Map<String,Object> body,String... allowed) {
        if(body==null || WarehouseJson.write(body).length()>32768)throw WarehouseException.invalid("Return request too large");
        var set=Set.of(allowed);for(String key:body.keySet())if(!set.contains(key))throw WarehouseException.invalid("Unknown or protected return field: "+key);
    }
    static String text(Map<String,Object> body,String key,int max,boolean required) {
        Object v=body.get(key);if(v!=null && !(v instanceof String))throw WarehouseException.invalid(key+" must be text");
        String s=v==null?"":v.toString().strip();if(s.length()>max||s.indexOf('\0')>=0||required&&s.isEmpty())throw WarehouseException.invalid(key+" is required or too long");return s;
    }
    static String id(Map<String,Object> body,String key) {String s=text(body,key,64,true);if(!s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}"))throw WarehouseException.invalid("Invalid "+key);return s;}
    static long quantity(Object value) {return WarehouseJson.integer(value,"quantity",1,10_000_000);}
    static long version(Map<String,Object> body) {return WarehouseJson.integer(body.get("_version"),"_version",1,9_007_199_254_740_991L);}
    static BigDecimal rate(Object value) {
        try {if(value==null||value instanceof Boolean||value.toString().isBlank())throw new IllegalArgumentException();var n=new BigDecimal(value.toString());if(n.signum()<0||n.scale()>6||n.compareTo(new BigDecimal("100000000"))>0)throw new IllegalArgumentException();return n;}
        catch(RuntimeException e){throw WarehouseException.conflict("Configure valid return first/additional rates / 请配置有效的退货首件及续件费用（免费请填 0）");}
    }
    static String hash(Object input) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(canonical(input)).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static Object canonical(Object v) {if(v instanceof Map<?,?> m){var n=new TreeMap<String,Object>();m.forEach((k,a)->n.put(k.toString(),canonical(a)));return n;}if(v instanceof List<?> l)return l.stream().map(ReturnInput::canonical).toList();return v;}
}
