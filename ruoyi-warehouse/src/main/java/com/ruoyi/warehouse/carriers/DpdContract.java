package com.ruoyi.warehouse.carriers;

import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** DPD Group UK customer API, not the retired GeoSession or another country's DPD API. */
public final class DpdContract {
    private DpdContract(){}
    public static final int MAX_LABEL_BYTES=2*1024*1024;
    public static Map<String,Object> object(Object raw){if(!(raw instanceof Map<?,?>))throw new CarrierFailure("DPD_RESPONSE_INVALID");return WarehouseJson.copy((Map<String,Object>)raw);}
    public static void keys(Map<String,Object> data,Set<String> allowed){if(data==null||!allowed.containsAll(data.keySet()))throw WarehouseException.invalid("Unexpected carrier fields / 快递资料包含不支持的字段");}
    public static String text(Object raw,String name,int max,boolean optional){
        if(optional&&(raw==null||"".equals(raw)))return "";
        if(!(raw instanceof String value)||value.isBlank()||value.length()>max||value.chars().anyMatch(c->c<32||c==127)||!wellFormed(value))throw WarehouseException.invalid("Invalid "+name+" / 请检查 "+name);
        return value.strip();
    }
    public static boolean wellFormed(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    public static String opaque(Object raw,int max){String s=raw instanceof String v?v:"";if(s.isEmpty()||s.codePointCount(0,s.length())>max||!wellFormed(s)||Pattern.compile("[\\s\\p{Cntrl}/\\\\?#]").matcher(s).find()||Set.of(".","..").contains(s))throw new CarrierFailure("DPD_IDENTITY_INVALID");return s;}
    public static Map<String,Object> credentials(Map<String,Object> data){
        if(data==null||!data.keySet().equals(Set.of("appKey","appSecret")))throw WarehouseException.invalid("Provide both DPD app key and secret / 请填写完整 DPD 凭据");
        var out=new LinkedHashMap<String,Object>();for(String k:List.of("appKey","appSecret")){String v=text(data.get(k),k,"appKey".equals(k)?1024:4096,false);if(!v.equals(data.get(k))||v.chars().anyMatch(c->c<33||c>126)||"appKey".equals(k)&&v.contains(":"))throw WarehouseException.invalid("Invalid DPD credentials");out.put(k,v);}return out;
    }
    public static Map<String,Object> address(Map<String,Object> raw){
        keys(raw,Set.of("name","company","address1","address2","town","county","postcode","country","phone","email","mobile"));
        if(!"GB".equals(raw.get("country")))throw new CarrierFailure("DPD_DOMESTIC_REQUIRED");
        var a=new LinkedHashMap<String,Object>();for(String key:List.of("name","company","address1","address2","town","county"))a.put(key,text(raw.get(key),key,35,Set.of("company","address2","county").contains(key)));
        String post=text(raw.get("postcode"),"postcode",8,false).toUpperCase(Locale.ROOT).replace(" ","");
        if(!post.matches("[A-Z]{1,2}[0-9][A-Z0-9]?[0-9][A-Z]{2}"))throw new CarrierFailure("DPD_POSTCODE_INVALID");
        if(post.matches("^(BT|GY|JE|IM|BF).*")||Pattern.compile("\\bP\\s*\\.?\\s*O\\s*\\.?\\s*BOX\\b",Pattern.CASE_INSENSITIVE).matcher(a.get("address1")+" "+a.get("address2")).find())throw new CarrierFailure("DPD_CUSTOMS_OR_SPECIAL_ADDRESS");
        a.put("postcode",post.substring(0,post.length()-3)+" "+post.substring(post.length()-3));a.put("country","GB");
        for(String key:List.of("phone","mobile")){String phone=text(raw.get(key),key,30,"mobile".equals(key)).replaceAll("[ ()-]","");if(!phone.isEmpty()&&!phone.matches("(?:\\+[0-9]{1,14}|[0-9]{7,15})"))throw new CarrierFailure("DPD_CONTACT_INVALID");a.put(key,phone);}
        String email=text(raw.get("email"),"email",100,true);if(!email.isEmpty()&&!email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+"))throw new CarrierFailure("DPD_CONTACT_INVALID");a.put("email",email);return a;
    }
    public static Map<String,Object> settings(Map<String,Object> raw){
        keys(raw,Set.of("environment","businessUnit","sender","printerDpi"));String environment=text(raw.get("environment"),"environment",16,false),unit=text(raw.get("businessUnit"),"businessUnit",16,false);
        if(!Set.of("sandbox","production").contains(environment)||!Set.of("DPD","DPDLocal").contains(unit))throw WarehouseException.invalid("Select the exact DPD environment and business / 请选择准确的 DPD 环境与业务类型");
        long dpi=WarehouseJson.integer(raw.getOrDefault("printerDpi",203),"printerDpi",203,300);if(dpi!=203&&dpi!=300)throw WarehouseException.invalid("DPD ZPL requires 203 or 300 DPI");
        return Map.of("environment",environment,"businessUnit",unit,"sender",address(object(raw.get("sender"))),"printerDpi",dpi);
    }
    public static String origin(String environment){return switch(environment){case "sandbox"->"https://developers.api.customers.dpd.co.uk";case "production"->"https://api.customers.dpd.co.uk";default->throw new CarrierFailure("DPD_ENVIRONMENT_INVALID");};}
    public static Map<String,Object> recipient(Map<String,Object> order,String mobile){
        if(!WarehouseJson.text(order,"address3").isBlank())throw new CarrierFailure("DPD_ADDRESS_REQUIRES_REVIEW");
        var raw=new LinkedHashMap<String,Object>();for(String key:List.of("name","address1","address2","town","county","postcode","country","phone","email"))raw.put(key,order.getOrDefault(key,""));raw.put("company",order.getOrDefault("companyName",""));raw.put("mobile",mobile);return address(raw);
    }
    public static BigDecimal totalWeight(List<Map<String,Object>> parcels){
        if(parcels.isEmpty()||parcels.size()>99)throw new CarrierFailure("DPD_PARCEL_COUNT_INVALID");BigDecimal total=BigDecimal.ZERO;
        for(var p:parcels){BigDecimal weight;try{weight=new BigDecimal(p.get("weightKg").toString());if(weight.signum()<=0||weight.compareTo(new BigDecimal("99999"))>0)throw new IllegalArgumentException();}catch(Exception e){throw new CarrierFailure("DPD_WEIGHT_INVALID");}total=total.add(weight);}
        if(total.compareTo(new BigDecimal("0.1"))<0||total.compareTo(new BigDecimal("99999"))>0)throw new CarrierFailure("DPD_WEIGHT_INVALID");return total;
    }
    public static String shipmentDate(String date,Instant now){try{LocalDate value=LocalDate.parse(date),today=now.atZone(ZoneId.of("Europe/London")).toLocalDate();if(value.isBefore(today)||value.isAfter(today.plusDays(7)))throw new IllegalArgumentException();return value+"T12:00:00";}catch(Exception e){throw new CarrierFailure("DPD_SHIP_DATE_INVALID");}}
    public static Map<String,Object> wireAddress(Map<String,Object> a){return Map.of("countryCode","GB","organisation",WarehouseJson.text(a,"company").isEmpty()?a.get("name"):a.get("company"),"street",a.get("address1"),"locality",a.get("address2"),"town",a.get("town"),"county",a.get("county"),"postcode",a.get("postcode"));}
    public static Map<String,Object> identity(Object body,int count){
        var data=object(object(body).get("data"));var consignments=data.get("consignments");if(!(consignments instanceof List<?> list)||list.size()!=1)throw new CarrierFailure("DPD_IDENTITY_INVALID");
        var c=object(list.get(0));String shipment=opaque(data.get("shipmentId"),40),consignment=opaque(c.get("consignmentNumber"),128);
        List<String> numbers=identifiers(c.get("parcelNumber"),count,14),ids=identifiers(c.get("parcelIds"),count,128);if(numbers.stream().anyMatch(s->s.codePointCount(0,s.length())!=14))throw new CarrierFailure("DPD_IDENTITY_INVALID");
        return Map.of("shipmentId",shipment,"consignmentNumber",consignment,"trackingNumbers",numbers,"parcelIds",ids);
    }
    private static List<String> identifiers(Object raw,int count,int max){if(!(raw instanceof List<?> list)||list.size()!=count)throw new CarrierFailure("DPD_IDENTITY_INVALID");var values=list.stream().map(v->opaque(v,max)).toList();if(new HashSet<>(values).size()!=count)throw new CarrierFailure("DPD_IDENTITY_INVALID");return values;}
    public static String label(Object body){
        var response=object(body);Object strings=response.containsKey("data")&&!response.containsKey("printString")?object(response.get("data")).get("printString"):!response.containsKey("data")?response.get("printString"):null;
        if(!(strings instanceof List<?> rows)||rows.size()!=1||!(rows.get(0) instanceof String zpl))throw new CarrierFailure("DPD_LABEL_INVALID");validateLabel(zpl);return zpl;
    }
    public static void validateLabel(String zpl){
        if(!wellFormed(zpl)||zpl.getBytes(StandardCharsets.UTF_8).length>MAX_LABEL_BYTES||Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]").matcher(zpl).find()||!Pattern.compile("\\s*\\^XA(?:(?!\\^XA|\\^XZ)[\\s\\S])*\\^XZ\\s*").matcher(zpl).matches())throw new CarrierFailure("DPD_LABEL_INVALID");
    }
}
