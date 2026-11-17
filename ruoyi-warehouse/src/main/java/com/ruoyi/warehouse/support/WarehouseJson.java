package com.ruoyi.warehouse.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

public final class WarehouseJson {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private WarehouseJson(){}
    public static Map<String,Object> map(String json){try{return JSON.readValue(json,new TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("Invalid stored warehouse JSON",e);}}
    public static String write(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Cannot encode warehouse JSON",e);}}
    public static Map<String,Object> copy(Map<String,Object> value){return map(write(value));}
    public static String text(Map<String,Object> value,String key){return Objects.toString(value.get(key),"");}
    public static boolean enabled(Object v){return Boolean.TRUE.equals(v)||"Y".equals(v)||"true".equals(v)||Integer.valueOf(1).equals(v);}
    public static boolean empty(Object v){return v==null||v instanceof String s&&s.isBlank();}
    public static long integer(Object value,String name,long min,long max){try{long n=new BigDecimal(String.valueOf(value)).longValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}catch(Exception e){throw WarehouseException.invalid(name+" must be an integer between "+min+" and "+max);}}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> maps(Object value){return value instanceof List<?> a?(List<Map<String,Object>>)(List<?>)a:List.of();}
}
