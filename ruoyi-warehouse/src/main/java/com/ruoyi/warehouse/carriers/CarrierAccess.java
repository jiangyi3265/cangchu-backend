package com.ruoyi.warehouse.carriers;

import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.WarehouseException;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CarrierAccess {
    final WarehouseRepository records;final WarehouseScope scope;
    public CarrierAccess(WarehouseRepository records,WarehouseScope scope){this.records=records;this.scope=scope;}
    void permission(String action,String client,String warehouse,boolean current){
        long actor=scope.actor();var jdbc=records.jdbc();String lock=current?" FOR UPDATE":"";
        if(jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'"+lock,Long.class,actor).isEmpty())throw WarehouseException.forbidden();
        if(actor==1)return;
        if(jdbc.queryForList("SELECT ur.role_id FROM sys_user_role ur JOIN sys_role r ON r.role_id=ur.role_id JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE ur.user_id=? AND r.status='0' AND r.del_flag='0' AND m.status='0' AND m.perms=?"+lock,Long.class,actor,action).isEmpty())throw WarehouseException.forbidden();
        var granted=jdbc.queryForList("SELECT scope_type FROM wh_user_scope WHERE user_id=? AND ((scope_type='CLIENT' AND scope_id=?) OR (scope_type='WAREHOUSE' AND scope_id=?))"+lock,String.class,actor,client,warehouse);if(!granted.containsAll(List.of("CLIENT","WAREHOUSE")))throw WarehouseException.forbidden();
    }
    void route(Map<String,Object> row){scope.assertRecord("carrierConnections",Map.of("client",row.get("client_id"),"warehouse",row.get("warehouse_id")));}
}
