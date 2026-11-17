package com.ruoyi.warehouse.data;

import com.ruoyi.warehouse.catalog.WarehouseCatalog;
import com.ruoyi.warehouse.support.WarehouseException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MasterDataValidationTest {
    final WarehouseCatalog catalog=new WarehouseCatalog();
    final MasterDataService service=new MasterDataService(catalog,null,null,null);
    @Test void calendarDatesAreStrictIncludingLeapYears(){
        var field=Map.<String,Object>of("key","date","type","date");assertEquals("2024-02-29",service.validateField(field,"2024-02-29"));
        for(String date:List.of("2025-02-29","2024-02-30","2024-13-01","2024-1-1","not-a-date"))assertThrows(WarehouseException.class,()->service.validateField(field,date));
    }
    @Test void numericInputsRejectObjectsNaNInfinityAndUnsafeIntegers(){
        var field=Map.<String,Object>of("key","quantity","type","number","step",1);
        for(Object value:List.of("NaN","Infinity",new Object(),true,"9007199254740992","1.5",-1))assertThrows(WarehouseException.class,()->service.validateField(field,value));
        assertEquals(new BigDecimal("0"),service.validateField(field,0));
    }
    @Test void moneyPreservesExplicitZeroAndNineDecimalPlaces(){
        var field=Map.<String,Object>of("key","firstPick","type","number");
        assertEquals("",service.validateField(field,""));assertEquals(new BigDecimal("0"),service.validateField(field,"0"));
        assertEquals(new BigDecimal("0.123456789"),service.validateField(field,"0.123456789"));assertThrows(WarehouseException.class,()->service.validateField(field,"0.1234567891"));
        assertThrows(WarehouseException.class,()->service.validateField(field,"1e1000000000"));
    }
    @Test void toggleCannotUseJavascriptTruthyCoercion(){
        var field=Map.<String,Object>of("key","active","type","toggle");assertEquals(false,service.validateField(field,"false"));assertEquals(false,service.validateField(field,"N"));assertEquals(true,service.validateField(field,"1"));assertThrows(WarehouseException.class,()->service.validateField(field,"off"));
    }
    @Test void objectValueEnumsAndDynamicLocationTypesRemainDistinct(){
        var field=Map.<String,Object>of("key","policy","type","select","options",List.of(Map.of("value","Warn","label","Warn only")));
        assertEquals("Warn",service.validateField(field,"Warn"));assertThrows(WarehouseException.class,()->service.validateField(field,"Warn only"));
        var type=catalog.fields("locations").stream().filter(f->"type".equals(f.get("key"))).findFirst().orElseThrow();assertEquals("CustomColdRoom",service.validateField(type,"CustomColdRoom"));
    }
    @Test void unsupportedWorkflowsCannotUseGenericWrites(){
        assertThrows(WarehouseException.class,()->catalog.editable("orders"));assertThrows(WarehouseException.class,()->catalog.editable("invoices"));assertThrows(WarehouseException.class,()->catalog.editable("orderTiers"));
        assertDoesNotThrow(()->catalog.editable("products"));
    }
    @Test void explicitNullExpiryPolicyIsRejectedDuringSave(){
        var fields=org.mockito.Mockito.mock(com.ruoyi.warehouse.security.WarehouseFields.class);
        var serviceWithAccess=new MasterDataService(catalog,null,null,fields);
        var input=new LinkedHashMap<String,Object>();input.put("shortName","TEST");input.put("name","Test client");input.put("expiryPolicy",null);
        assertThrows(WarehouseException.class,()->serviceWithAccess.save("clients",null,input));
    }
}
