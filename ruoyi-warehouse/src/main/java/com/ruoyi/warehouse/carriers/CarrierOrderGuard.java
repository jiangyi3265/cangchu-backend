package com.ruoyi.warehouse.carriers;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.support.WarehouseException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CarrierOrderGuard {
    private final WarehouseRepository records;
    private final CarrierAccess access;
    public CarrierOrderGuard(WarehouseRepository records,CarrierAccess access){this.records=records;this.access=access;}
    public void authorize(String action,Map<String,Object> order){access.permission(action,(String)order.get("client"),(String)order.get("warehouse"),true);}
    public void available(String order,String ownedShipment){
        // Caller holds the order row. A current read also sees bookings committed after an earlier snapshot.
        var ids=records.jdbc().queryForList("SELECT id FROM wh_carrier_shipment WHERE order_id=? AND state<>'REJECTED' ORDER BY id FOR UPDATE",String.class,order);
        if(ids.stream().anyMatch(id->!id.equals(ownedShipment)))throw WarehouseException.conflict("A carrier shipment already protects this order; reconcile it before changing warehouse work / 本单已有快递申请，请先核对快递结果，不能直接修改仓库作业");
    }
    public Map<String,Object> summary(String order){var rows=records.jdbc().queryForList("SELECT id,state,error_code FROM wh_carrier_shipment WHERE order_id=? AND state<>'REJECTED' ORDER BY created_at DESC LIMIT 1",order);return rows.isEmpty()?Map.of():rows.get(0);}
}
