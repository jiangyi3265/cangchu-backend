package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.support.WarehouseJson;
import java.math.*;
import java.time.*;
import java.util.*;

/** wc/v3 order intake only. A received document is never permission to ship it. */
public final class WooCommerceReader {
    public record Page(List<Map<String,Object>> orders,Map<String,Object> next,int total) { }
    private WooCommerceReader() { }
    public static Page fetch(IntegrationTransport.Reader transport,Map<String,Object> window){
        int page=integer(window.get("page"),1,10000),size=10;
        var query=new LinkedHashMap<String,String>();query.put("per_page","10");query.put("page",String.valueOf(page));query.put("orderby","id");query.put("order","asc");query.put("status","any");query.put("dates_are_gmt","true");query.put("modified_after",text(window,"since",40));query.put("modified_before",text(window,"until",40));
        var response=transport.get("/orders",query);int status=response.status();
        if(status==401||status==403)throw fail("REMOTE_AUTH_DENIED");if(status==429)throw fail("REMOTE_RATE_LIMITED");if(status<200||status>=300)throw fail("REMOTE_HTTP_ERROR");
        List<?> rows=array(response.body(),10);int total=integer(response.headers().get("x-wp-total"),0,100000),pages=integer(response.headers().get("x-wp-totalpages"),0,10000);
        if(window.containsKey("total")&&integer(window.get("total"),0,100000)!=total)throw fail("REMOTE_PAGESET_CHANGED");
        if(pages!=(total+size-1)/size||page>Math.max(1,pages)||rows.size()!=Math.min(size,Math.max(0,total-(page-1)*size)))throw fail("REMOTE_PAGINATION_INVALID");
        var orders=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();
        Instant since=instant(text(window,"since",40),false),until=instant(text(window,"until",40),false);
        for(Object raw:rows){var order=normalize(object(raw));if(!seen.add(text(order,"externalId",64)))throw fail("REMOTE_RESPONSE_INVALID");Instant changed=instant(text(order,"updatedAt",40),false);if(changed.isBefore(since)||changed.isAfter(until))throw fail("REMOTE_PAGESET_CHANGED");orders.add(order);}
        var next=new LinkedHashMap<String,Object>();if(page<pages){next.putAll(window);next.put("page",page+1);next.put("total",total);}else next.put("completedUntil",window.get("until"));
        return new Page(List.copyOf(orders),next,total);
    }
    static Map<String,Object> normalize(Map<String,Object> raw){
        var out=new LinkedHashMap<String,Object>();var reasons=new LinkedHashSet<String>();
        out.put("externalId",id(raw.get("id")));out.put("reference",text(raw,"number",191));out.put("updatedAt",instant(text(raw,"date_modified_gmt",40),true).toString());out.put("placedAt",instant(text(raw,"date_created_gmt",40),true).toString());
        String status=text(raw,"status",64),currency=text(raw,"currency",3);if(!currency.matches("[A-Z]{3}"))throw fail("REMOTE_RESPONSE_INVALID");out.put("sourceStatus",status);out.put("currency",currency);out.put("total",money(raw.get("total")));out.put("verification","ORDER_DETAILS_ONLY");
        boolean paid=raw.get("date_paid_gmt") instanceof String s&&!s.isBlank();if(paid)out.put("paidAt",instant(text(raw,"date_paid_gmt",40),true).toString());
        String state=Set.of("cancelled","refunded","failed","trash").contains(status)?"CANCELLED":"completed".equals(status)?"FULFILLED":"processing".equals(status)&&paid?"AWAITING_REVIEW":"HELD";
        if(!"processing".equals(status)&&!Set.of("CANCELLED","FULFILLED").contains(state))reasons.add("SOURCE_NOT_PROCESSING");if(!paid)reasons.add("PAYMENT_UNCONFIRMED");
        if(!(raw.get("refunds") instanceof List<?> refunds))reasons.add("REFUND_INFORMATION_MISSING");else if(!refunds.isEmpty())reasons.add("REFUNDS_PRESENT");
        inspectMetadata(raw.get("meta_data"),reasons,false);
        Map<String,Object> shipping=objectOrEmpty(raw.get("shipping")),billing=objectOrEmpty(raw.get("billing"));var address=new LinkedHashMap<String,Object>();
        String recipient=(optional(shipping,"first_name",255)+" "+optional(shipping,"last_name",255)).strip();if(recipient.isEmpty())recipient=optional(shipping,"company",255);address.put("name",recipient);
        for(var pair:List.of(new String[]{"address_1","address1"},new String[]{"address_2","address2"},new String[]{"city","town"},new String[]{"state","county"},new String[]{"postcode","postcode"},new String[]{"country","country"},new String[]{"company","companyName"}))address.put(pair[1],optional(shipping,pair[0],255));
        address.put("email",optional(billing,"email",255));String phone=optional(shipping,"phone",255);address.put("phone",phone.isEmpty()?optional(billing,"phone",255):phone);
        if(recipient.isEmpty()||String.valueOf(address.get("address1")).isEmpty()||String.valueOf(address.get("town")).isEmpty()||!String.valueOf(address.get("country")).matches("[A-Z]{2}"))reasons.add("SHIPPING_ADDRESS_INCOMPLETE");out.put("shipping",address);
        List<?> rawLines=array(raw.get("line_items"),2000);if(rawLines.isEmpty())reasons.add("ORDER_LINES_MISSING");if(rawLines.size()>200)reasons.add("TOO_MANY_ORDER_LINES");out.put("sourceLineCount",rawLines.size());
        var lines=new ArrayList<Map<String,Object>>();var seen=new HashSet<String>();for(Object value:rawLines.subList(0,Math.min(200,rawLines.size()))){var row=object(value);var line=new LinkedHashMap<String,Object>();String lineId=id(row.get("id"));if(!seen.add(lineId))throw fail("REMOTE_RESPONSE_INVALID");line.put("externalLineId",lineId);line.put("sku",optional(row,"sku",191));line.put("quantity",integer(row.get("quantity"),1,10000000));line.put("total",money(row.get("total")));line.put("productId",zeroId(row.get("product_id")));line.put("variationId",zeroId(row.get("variation_id")));line.put("name",optional(row,"name",500));if("".equals(line.get("sku")))reasons.add("SKU_MISSING");if("0".equals(line.get("productId")))reasons.add("PRODUCT_UNAVAILABLE");inspectMetadata(row.get("meta_data"),reasons,true);lines.add(line);}
        if("AWAITING_REVIEW".equals(state)&&!reasons.isEmpty())state="HELD";out.put("state",state);out.put("reasons",List.copyOf(reasons));out.put("lines",lines);return out;
    }
    private static void inspectMetadata(Object metadata,Set<String> reasons,boolean line){if(!(metadata instanceof List<?> rows)||rows.size()>2000){reasons.add("METADATA_INCOMPLETE");return;}for(Object value:rows){var item=object(value);String key=text(item,"key",200);if(!item.containsKey("value")){reasons.add("METADATA_INCOMPLETE");continue;}String lower=key.toLowerCase(Locale.ROOT);if(line&&(lower.contains("bundled_")||lower.contains("composite_")))reasons.add("BUNDLE_REQUIRES_REVIEW");Object v=item.get("value");if(!line&&(lower.contains("shipment_tracking")||lower.contains("tracking_number")||lower.contains("tracking_items")||lower.contains("fulfilled")||lower.contains("shipped"))&&v!=null&&!Boolean.FALSE.equals(v)&&!"".equals(v)&&!"0".equals(String.valueOf(v))&&!(v instanceof List<?> list&&list.isEmpty()))reasons.add("EXISTING_SHIPMENT_INFORMATION");}}
    static Instant instant(String value,boolean gmt){try{if(gmt&&value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?"))return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);return Instant.parse(value);}catch(Exception e){throw fail("REMOTE_TIMESTAMP_INVALID");}}
    private static String money(Object value){if(!(value instanceof String s)||!s.matches("[0-9]{1,22}(\\.[0-9]{1,9})?"))throw fail("REMOTE_MONEY_INVALID");return new BigDecimal(s).toPlainString();}
    private static String zeroId(Object value){return value==null||"0".equals(String.valueOf(value))?"0":id(value);}
    private static String id(Object value){String s=String.valueOf(value);if(!s.matches("[1-9][0-9]{0,18}"))throw fail("REMOTE_IDENTIFIER_INVALID");return s;}
    static int integer(Object value,int min,int max){try{int n=new BigDecimal(String.valueOf(value)).intValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}catch(Exception e){throw fail("REMOTE_RESPONSE_INVALID");}}
    private static List<?> array(Object value,int max){if(!(value instanceof List<?> a)||a.size()>max)throw fail("REMOTE_RESPONSE_INVALID");return a;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> map))throw fail("REMOTE_RESPONSE_INVALID");return (Map<String,Object>)map;}
    private static Map<String,Object> objectOrEmpty(Object value){return value==null?Map.of():object(value);}
    private static String text(Map<String,Object> row,String key,int max){String s=optional(row,key,max);if(s.isEmpty())throw fail("REMOTE_RESPONSE_INVALID");return s;}
    private static String optional(Map<String,Object> row,String key,int max){Object v=row.get(key);if(v==null)return "";if(!(v instanceof String s)||s.length()>max||s.chars().anyMatch(c->c<32&&c!='\n'&&c!='\t'||c==127))throw fail("REMOTE_RESPONSE_INVALID");return s.strip();}
    private static IntegrationFailure fail(String code){return new IntegrationFailure(code);}
}
