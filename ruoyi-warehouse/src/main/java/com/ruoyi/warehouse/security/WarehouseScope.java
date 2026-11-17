package com.ruoyi.warehouse.security;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.warehouse.support.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WarehouseScope {
    private final JdbcTemplate jdbc;
    public WarehouseScope(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public long actor(){return SecurityUtils.getUserId();}
    public boolean admin(){return SecurityUtils.isAdmin(actor());}
    public boolean granted(String type,String id){return admin()||jdbc.queryForObject("SELECT COUNT(*) FROM wh_user_scope WHERE user_id=? AND scope_type=? AND scope_id=?",Long.class,actor(),type,id)>0;}
    public void assertRecord(String kind,Map<String,Object> row){
        if(admin())return;
        if(sharedReadable(kind))return;
        if("locationTypes".equals(kind)&&SecurityUtils.hasPermi("warehouse:locationTypes:list"))return;
        String client="clients".equals(kind)?WarehouseJson.text(row,"id"):WarehouseJson.text(row,"client");
        String warehouse="warehouses".equals(kind)?WarehouseJson.text(row,"id"):WarehouseJson.text(row,"warehouse");
        if(!client.isEmpty()&&!granted("CLIENT",client)||!warehouse.isEmpty()&&!granted("WAREHOUSE",warehouse))throw WarehouseException.forbidden();
        if(client.isEmpty()&&warehouse.isEmpty())throw WarehouseException.forbidden();
        String location=WarehouseJson.text(row,"defaultLocation");
        if(!location.isEmpty()){
            var targets=jdbc.queryForList("SELECT warehouse_id FROM wh_record WHERE kind='locations' AND id=?",String.class,location);
            if(targets.size()!=1||!granted("WAREHOUSE",targets.get(0)))throw WarehouseException.forbidden();
        }
        String product=WarehouseJson.text(row,"product");
        if("warehousePrices".equals(kind)&&!product.isEmpty()){
            var products=jdbc.queryForList("SELECT business_json FROM wh_record WHERE kind='products' AND id=?",String.class,product);
            if(products.size()!=1)throw WarehouseException.forbidden();assertRecord("products",WarehouseJson.map(products.get(0)));
        }
    }
    private boolean sharedReadable(String kind){return Set.of("suppliers","channels").contains(kind)&&(SecurityUtils.hasPermi("warehouse:"+kind+":list")||SecurityUtils.hasPermi("warehouse:"+kind+":query"));}
    public void requireAdministrator(){if(!admin())throw WarehouseException.forbidden();}
    public String predicate(String kind,List<Object> params){
        if(admin())return "1=1";
        if(sharedReadable(kind))return "1=1";
        if("locationTypes".equals(kind)&&SecurityUtils.hasPermi("warehouse:locationTypes:list"))return "1=1";
        long user=actor();
        if("clients".equals(kind)){params.add(user);return "r.id IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='CLIENT')";}
        if("warehouses".equals(kind)){params.add(user);return "r.id IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE')";}
        params.add(user);params.add(user);
        String own="(r.client_id<>'' OR r.warehouse_id<>'') AND (r.client_id='' OR r.client_id IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='CLIENT')) AND (r.warehouse_id='' OR r.warehouse_id IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE'))";
        if("products".equals(kind)){params.add(user);own+=" AND NOT EXISTS (SELECT 1 FROM wh_reference ref JOIN wh_record target ON target.id=ref.target_id WHERE ref.owner_id=r.id AND ref.field_name='defaultLocation' AND target.warehouse_id NOT IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE'))";}
        if("warehousePrices".equals(kind)){
            params.add(user);params.add(user);
            own+=" AND NOT EXISTS (SELECT 1 FROM wh_reference product_ref JOIN wh_record product_target ON product_target.id=product_ref.target_id WHERE product_ref.owner_id=r.id AND product_ref.field_name='product' AND (product_target.kind<>'products' OR product_target.client_id NOT IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='CLIENT') OR EXISTS (SELECT 1 FROM wh_reference location_ref JOIN wh_record location_target ON location_target.id=location_ref.target_id WHERE location_ref.owner_id=product_target.id AND location_ref.field_name='defaultLocation' AND (location_target.kind<>'locations' OR location_target.warehouse_id NOT IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE')))))";
        }
        return own;
    }
    public String inventoryPredicate(List<Object> params){if(admin())return "1=1";params.add(actor());params.add(actor());return "i.client_id IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='CLIENT') AND i.warehouse_id IN (SELECT scope_id FROM wh_user_scope WHERE user_id=? AND scope_type='WAREHOUSE')";}
}
