package com.ruoyi.warehouse.finance;

import com.ruoyi.warehouse.support.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

final class FinanceInput {
    private FinanceInput() { }
    static void keys(Map<String,Object> input,String... allowed) {
        if(input==null||WarehouseJson.write(input).length()>262144)throw WarehouseException.invalid("Invalid finance request size");
        var keys=Set.of(allowed);for(String key:input.keySet())if(!keys.contains(key))throw WarehouseException.invalid("Unknown or protected field: "+key);
    }
    static String text(Map<String,Object> m,String key,int max,boolean required){if(m==null)throw WarehouseException.invalid("Finance request is required");Object v=m.get(key);if(v==null){if(required)throw WarehouseException.invalid(key+" is required");return "";}if(!(v instanceof String))throw WarehouseException.invalid(key+" must be text");String s=((String)v).strip();if(s.length()>max||s.indexOf('\0')>=0||required&&s.isEmpty())throw WarehouseException.invalid("Invalid "+key);return s;}
    static String id(Map<String,Object> m,String key){String s=text(m,key,64,true);if(!s.matches("[A-Za-z0-9_-]+"))throw WarehouseException.invalid("Invalid "+key);return s;}
    static BigDecimal number(Object v,String key,boolean negative){try{if(!(v instanceof Number||v instanceof String))throw new IllegalArgumentException();BigDecimal n=new BigDecimal(v.toString());if(n.precision()>24||n.scale()>9||(long)n.precision()-(long)n.scale()>22||!negative&&n.signum()<0)throw new IllegalArgumentException();return n;}catch(Exception e){throw WarehouseException.invalid("Invalid "+key);}}
    static BigDecimal money(Object v){BigDecimal n=number(v,"amount",true).setScale(2,RoundingMode.HALF_EVEN);if((long)n.precision()-n.scale()>22)throw WarehouseException.invalid("Amount exceeds DECIMAL(24,2)");return n;}
    static boolean bool(Map<String,Object> m,String key,boolean fallback){if(!m.containsKey(key))return fallback;if(!(m.get(key) instanceof Boolean b))throw WarehouseException.invalid(key+" must be boolean");return b;}
    static String option(Map<String,Object> m,String key,String fallback,String... choices){String v=m.containsKey(key)?text(m,key,40,true):fallback;if(!Set.of(choices).contains(v))throw WarehouseException.invalid("Invalid "+key);return v;}
    static LocalDate date(Map<String,Object> m,String key){String s=text(m,key,10,true);try{if(!s.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();return LocalDate.parse(s);}catch(Exception e){throw WarehouseException.invalid("Invalid "+key);}}
    static String hash(Object o){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(canonical(o)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Object canonical(Object o){if(o instanceof Map<?,?> m){var r=new TreeMap<String,Object>();m.forEach((k,v)->r.put(String.valueOf(k),canonical(v)));return r;}if(o instanceof List<?> l)return l.stream().map(FinanceInput::canonical).toList();return o;}
    static Map<String,Object> business(Map<String,Object> row){var result=WarehouseJson.copy(row);for(String k:List.of("id","_version","createdAt","updatedAt"))result.remove(k);return result;}
}
