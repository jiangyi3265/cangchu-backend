package com.ruoyi.warehouse.finance;
import java.math.*;
import java.util.*;

/** Preserve exact decimal text across JavaScript clients; evidence remains numeric. */
final class FinanceDto {
    private FinanceDto() { }
    static Object value(Object input){
        if(input instanceof BigDecimal decimal)return decimal.toPlainString();
        if(input instanceof BigInteger integer)return integer.toString();
        if(input instanceof Long integer&&(integer>9007199254740991L||integer< -9007199254740991L))return integer.toString();
        if(input instanceof Map<?,?> source){var result=new LinkedHashMap<String,Object>();source.forEach((k,v)->result.put(String.valueOf(k),value(v)));return result;}
        if(input instanceof List<?> list)return list.stream().map(FinanceDto::value).toList();
        return input;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Map<String,Object> input){return (Map<String,Object>)value(input);}
}
