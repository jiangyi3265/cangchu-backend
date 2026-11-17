package com.ruoyi.warehouse.integrations;

import com.ruoyi.warehouse.support.WarehouseJson;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WooCommerceVerificationTest {
    Map<String,Object> raw=WooCommerceReaderTest.order();
    Map<String,Object> product=new LinkedHashMap<>(Map.of("id",1,"sku","000012","type","simple","status","publish","virtual",false,"shipping_required",true,"meta_data",List.of()));
    IntegrationTransport.Response fulfillment=new IntegrationTransport.Response(200,List.of(),Map.of());
    Map<String,Object> variation;
    IntegrationTransport.Reader reader=(path,query)-> {
        if(path.endsWith("/fulfillments"))return fulfillment;
        if(path.startsWith("/orders/"))return response(raw);
        if(path.equals("/products/1"))return response(product);
        if(path.equals("/products/1/variations/2"))return response(variation);
        throw new AssertionError(path);
    };
    static IntegrationTransport.Response response(Object value){return new IntegrationTransport.Response(200,value,Map.of());}
    Map<String,Object> inspect(String mode){return WooCommerceVerification.inspect(reader,WooCommerceReader.normalize(raw),mode);}
    void fails(String code,Runnable run){assertEquals(code,assertThrows(IntegrationFailure.class,run::run).code());}

    @Test void verifiesPhysicalIdentityAndEmptyCoreFulfillmentsWithoutWriting(){var result=inspect("CORE");assertEquals("CORE_EMPTY",result.get("fulfillments"));assertEquals(true,WarehouseJson.maps(result.get("lines")).get(0).get("physical"));assertEquals("9007199254740993",((Map<?,?>)result.get("order")).get("externalId"));}
    @Test void anyExistingFulfillmentIncludesDraftOwnershipAndStopsImport(){fulfillment=response(List.of(Map.of("id",7,"status","unfulfilled","is_fulfilled",false)));fails("EXISTING_FULFILLMENT",()->inspect("CORE"));}
    @Test void unavailableCoreRouteNeedsExplicitLegacyPolicyAndExactErrorCode(){fulfillment=new IntegrationTransport.Response(404,Map.of("code","rest_no_route"),Map.of());fails("CORE_FULFILLMENTS_UNAVAILABLE",()->inspect("CORE"));assertEquals("CORE_ROUTE_UNAVAILABLE_LEGACY_POLICY",inspect("LEGACY_METADATA").get("fulfillments"));fulfillment=new IntegrationTransport.Response(404,Map.of("code","unknown"),Map.of());fails("REMOTE_HTTP_ERROR",()->inspect("LEGACY_METADATA"));}
    @Test void sourceSkuIdentityAndUnsupportedProductTypesAreNotGuessed(){product.put("sku","different");fails("SOURCE_PRODUCT_CHANGED",()->inspect("CORE"));product.put("sku","000012");product.put("type","bundle");fails("SOURCE_PRODUCT_TYPE_UNSUPPORTED",()->inspect("CORE"));product.put("type","simple");product.put("meta_data",List.of(Map.of("key","_subscription_period","value","month")));fails("SOURCE_PRODUCT_TYPE_UNSUPPORTED",()->inspect("CORE"));}
    @Test void variationUsesItsOwnPhysicalFlagAndItsParentIdentity(){product.put("type","variable");WarehouseJson.maps(raw.get("line_items")).get(0).put("variation_id",2);variation=new LinkedHashMap<>(Map.of("id",2,"sku","000012","status","publish","virtual",false,"meta_data",List.of()));assertEquals("2",WarehouseJson.maps(inspect("CORE").get("lines")).get(0).get("variationId"));variation.put("id",3);fails("SOURCE_PRODUCT_CHANGED",()->inspect("CORE"));}
    @Test void virtualLinesDoNotRequireSkuMappingsButAnEntirelyVirtualOrderCannotShip(){product.put("virtual",true);product.put("shipping_required",false);product.put("sku","");WarehouseJson.maps(raw.get("line_items")).get(0).put("sku","");fails("NO_PHYSICAL_LINES",()->inspect("CORE"));}
    @Test void missingOrContradictoryPhysicalFlagsCannotPass(){product.remove("virtual");fails("SOURCE_PRODUCT_UNVERIFIED",()->inspect("CORE"));product.put("virtual",false);product.put("shipping_required",false);fails("SOURCE_PRODUCT_UNVERIFIED",()->inspect("CORE"));}
    @Test void aChangeWhileReadingProductsIsDetectedByTheSecondOrderRead(){var expected=WooCommerceReader.normalize(raw);var calls=new AtomicInteger();IntegrationTransport.Reader changing=(path,query)->{if(path.startsWith("/orders/")&&!path.endsWith("/fulfillments")&&calls.incrementAndGet()==2)raw.put("status","cancelled");return reader.get(path,query);};fails("SOURCE_ORDER_CHANGED",()->WooCommerceVerification.inspect(changing,expected,"CORE"));}
    @Test void cancellationsAndRefundsAndUnpaidOrdersCannotEnterWarehouse(){raw.put("refunds",List.of(Map.of("id",1)));fails("SOURCE_ORDER_HELD",()->inspect("CORE"));raw.put("refunds",List.of());raw.put("status","cancelled");fails("SOURCE_ORDER_HELD",()->inspect("CORE"));raw.put("status","processing");raw.put("date_paid_gmt",null);fails("SOURCE_ORDER_HELD",()->inspect("CORE"));}
}
