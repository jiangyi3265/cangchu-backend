package com.ruoyi.warehouse.data;
import com.ruoyi.warehouse.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
final class MasterImportInput {
    static void keys(Map<String,Object> input,String... names){if(input==null||WarehouseJson.write(input).length()>2048)throw WarehouseException.invalid("Invalid import action");var allowed=Set.of(names);for(String key:input.keySet())if(!allowed.contains(key))throw WarehouseException.invalid("Unknown import field: "+key);}
    static String id(Map<String,Object> input,String key){Object value=input.get(key);if(!(value instanceof String s)||!s.matches("[A-Za-z0-9_-]{1,64}"))throw WarehouseException.invalid("Invalid "+key);return s;}
    static String option(Map<String,Object> input,String key,String fallback,String... choices){Object value=input.getOrDefault(key,fallback);if(!(value instanceof String s)||!Set.of(choices).contains(s))throw WarehouseException.invalid("Invalid "+key);return s;}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(canonical(value)).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Object canonical(Object value){if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(String.valueOf(k),canonical(v)));return sorted;}if(value instanceof List<?> list)return list.stream().map(MasterImportInput::canonical).toList();return value;}
}
