package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.support.WarehouseJson;
import java.util.*;

/** Re-read the paid order, physical products and existing fulfilments before warehouse work. */
public final class WooCommerceVerification {
    private WooCommerceVerification() { }

    public static Map<String,Object> inspect(IntegrationTransport.Reader reader, Map<String,Object> expected, String mode) {
        String id=String.valueOf(expected.get("externalId"));
        if(!id.matches("[1-9][0-9]{0,18}"))throw new IntegrationFailure("REMOTE_IDENTIFIER_INVALID");
        var first=readOrder(reader,id);
        if(!fingerprint(first).equals(fingerprint(expected)))throw new IntegrationFailure("SOURCE_ORDER_CHANGED");
        if(!"AWAITING_REVIEW".equals(first.get("state"))&&!("HELD".equals(first.get("state"))&&List.of("SKU_MISSING").equals(first.get("reasons"))))throw new IntegrationFailure("SOURCE_ORDER_HELD");
        var products=new HashMap<String,Map<String,Object>>();
        var checks=new ArrayList<Map<String,Object>>();
        for(var line:WarehouseJson.maps(first.get("lines"))) {
            String product=String.valueOf(line.get("productId")),variation=String.valueOf(line.get("variationId"));
            if(!product.matches("[1-9][0-9]{0,18}")||!variation.matches("0|[1-9][0-9]{0,18}"))throw new IntegrationFailure("REMOTE_IDENTIFIER_INVALID");
            var parent=products.computeIfAbsent(product,p->object(success(reader.get("/products/"+p,Map.of("context","edit")))));
            if(!product.equals(String.valueOf(parent.get("id"))))throw new IntegrationFailure("SOURCE_PRODUCT_CHANGED");
            boolean variant=!"0".equals(variation);
            if(!(variant?"variable":"simple").equals(parent.get("type")))throw new IntegrationFailure("SOURCE_PRODUCT_TYPE_UNSUPPORTED");
            var actual=variant?products.computeIfAbsent(product+":"+variation,p->object(success(reader.get("/products/"+product+"/variations/"+variation,Map.of("context","edit"))))):parent;
            if(!(variant?variation:product).equals(String.valueOf(actual.get("id")))||!Objects.equals(line.get("sku"),actual.get("sku")))throw new IntegrationFailure("SOURCE_PRODUCT_CHANGED");
            if(!"publish".equals(actual.get("status"))||!"publish".equals(parent.get("status")))throw new IntegrationFailure("SOURCE_PRODUCT_INACTIVE");
            if(!(actual.get("virtual") instanceof Boolean virtual))throw new IntegrationFailure("SOURCE_PRODUCT_UNVERIFIED");
            if(!virtual&&String.valueOf(line.get("sku")).isBlank())throw new IntegrationFailure("PHYSICAL_SKU_MISSING");
            if(!variant&&(!(actual.get("shipping_required") instanceof Boolean shipping)||shipping==virtual))throw new IntegrationFailure("SOURCE_PRODUCT_UNVERIFIED");
            rejectAdvancedMetadata(parent);if(variant)rejectAdvancedMetadata(actual);
            checks.add(Map.of("externalLineId",line.get("externalLineId"),"productId",product,"variationId",variation,"sku",line.get("sku"),"quantity",line.get("quantity"),"physical",!virtual));
        }
        String fulfillments=fulfillments(reader,id,mode);
        // No remote transaction spans these GETs. Re-read to detect changes during verification.
        if(!fingerprint(first).equals(fingerprint(readOrder(reader,id))))throw new IntegrationFailure("SOURCE_ORDER_CHANGED");
        if(checks.stream().noneMatch(row->Boolean.TRUE.equals(row.get("physical"))))throw new IntegrationFailure("NO_PHYSICAL_LINES");
        return Map.of("order",first,"fingerprint",fingerprint(first),"lines",checks,"fulfillments",fulfillments);
    }

    static String fingerprint(Map<String,Object> order) {
        var stable=new LinkedHashMap<>(order);stable.remove("updatedAt");stable.remove("verification");
        return IntegrationService.hash(IntegrationService.canonical(stable));
    }
    private static Map<String,Object> readOrder(IntegrationTransport.Reader reader,String id) {
        var order=WooCommerceReader.normalize(object(success(reader.get("/orders/"+id,Map.of("context","edit")))));
        if(!id.equals(order.get("externalId")))throw new IntegrationFailure("REMOTE_IDENTIFIER_INVALID");
        return order;
    }
    private static String fulfillments(IntegrationTransport.Reader reader,String id,String mode) {
        var response=reader.get("/orders/"+id+"/fulfillments",Map.of());
        if(response.status()==404&&response.body() instanceof Map<?,?> map&&"rest_no_route".equals(map.get("code"))) {
            if("LEGACY_METADATA".equals(mode))return "CORE_ROUTE_UNAVAILABLE_LEGACY_POLICY";
            throw new IntegrationFailure("CORE_FULFILLMENTS_UNAVAILABLE");
        }
        Object body=success(response);
        if(!(body instanceof List<?> rows))throw new IntegrationFailure("REMOTE_RESPONSE_INVALID");
        // Even an unfulfilled/draft record may already be owned by another fulfilment process.
        if(!rows.isEmpty())throw new IntegrationFailure("EXISTING_FULFILLMENT");
        return "CORE_EMPTY";
    }
    private static void rejectAdvancedMetadata(Map<String,Object> row) {
        if(!(row.get("meta_data") instanceof List<?> rows)||rows.size()>2000)throw new IntegrationFailure("SOURCE_PRODUCT_UNVERIFIED");
        for(Object raw:rows) {
            var data=object(raw);Object key=data.get("key");if(!(key instanceof String s))throw new IntegrationFailure("SOURCE_PRODUCT_UNVERIFIED");
            String lower=s.toLowerCase(Locale.ROOT);
            if(lower.contains("bundle")||lower.contains("composite")||lower.contains("subscription"))throw new IntegrationFailure("SOURCE_PRODUCT_TYPE_UNSUPPORTED");
        }
    }
    private static Object success(IntegrationTransport.Response response) {
        if(response.status()==401||response.status()==403)throw new IntegrationFailure("REMOTE_AUTH_DENIED");
        if(response.status()==429)throw new IntegrationFailure("REMOTE_RATE_LIMITED");
        if(response.status()<200||response.status()>=300)throw new IntegrationFailure("REMOTE_HTTP_ERROR");
        return response.body();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?> row))throw new IntegrationFailure("REMOTE_RESPONSE_INVALID");
        return (Map<String,Object>)row;
    }
}
