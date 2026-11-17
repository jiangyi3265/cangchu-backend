package com.ruoyi.warehouse.stocktake;

import com.ruoyi.warehouse.support.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class StocktakeInput {
    private StocktakeInput() { }
    static void keys(Map<String,Object> body, String... allowed) {
        if (body == null || WarehouseJson.write(body).length() > 524288) throw WarehouseException.invalid("Stocktake request too large");
        var fields = Set.of(allowed);
        for (String key : body.keySet()) if (!fields.contains(key)) throw WarehouseException.invalid("Unknown or protected count field: " + key);
    }
    static String text(Map<String,Object> body, String key, int maximum, boolean required) {
        Object value=body.get(key);
        if (value != null && !(value instanceof String)) throw WarehouseException.invalid(key + " must be text");
        String result=value==null ? "" : value.toString().strip();
        if (result.length()>maximum || result.indexOf('\0')>=0 || required && result.isEmpty()) throw WarehouseException.invalid(key + " is required or too long");
        return result;
    }
    static String id(Map<String,Object> body,String key) {
        String result=text(body,key,64,true);
        if (!result.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}")) throw WarehouseException.invalid("Invalid " + key);
        return result;
    }
    static long quantity(Object value) { return WarehouseJson.integer(value,"counted",0,9_007_199_254_740_991L); }
    static String hash(Object input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(WarehouseJson.write(canonical(input)).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map){var out=new TreeMap<String,Object>();map.forEach((k,v)->out.put(k.toString(),canonical(v)));return out;}
        if(value instanceof List<?> list)return list.stream().map(StocktakeInput::canonical).toList();
        return value;
    }
}
