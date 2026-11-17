package com.ruoyi.warehouse.inventory;

import com.ruoyi.warehouse.support.WarehouseException;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTrackingTest {
    InventoryMovement movement(String action,String serial,long quantity){return new InventoryMovement("req",action,"p","w","l",quantity,"test","",serial,"","w2","l2");}
    @Test void optionalInboundTrackingMayRemainBlank(){
        var optional=Map.<String,Object>of("hasBatch",true,"hasSerial",true,"hasExpiry",true,"logBatchInbound","Optional","logSerialInbound","Optional","logExpiryInbound","Optional");
        assertDoesNotThrow(()->movement("add","",5).validateTracking(optional));
        assertThrows(WarehouseException.class,()->movement("add","",1).validateTracking(Map.of("hasBatch",true)));
        assertThrows(WarehouseException.class,()->movement("add","",1).validateTracking(Map.of("hasSerial",true,"logSerialInbound","Required")));
        assertThrows(WarehouseException.class,()->movement("add","",1).validateTracking(Map.of("hasExpiry",true)));
    }
    @Test void existingBlankPoolsCanBeRemovedOrTransferredAfterPolicyChanges(){
        var required=Map.<String,Object>of("hasBatch",true,"hasSerial",true,"hasExpiry",true);
        assertDoesNotThrow(()->movement("remove","",5).validateTracking(required));
        assertDoesNotThrow(()->movement("transfer","",5).validateTracking(required));
    }
}
