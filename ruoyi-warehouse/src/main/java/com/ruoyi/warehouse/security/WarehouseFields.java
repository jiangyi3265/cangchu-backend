package com.ruoyi.warehouse.security;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.warehouse.catalog.WarehouseCatalog;
import com.ruoyi.warehouse.support.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class WarehouseFields {
    private final WarehouseCatalog catalog;
    public WarehouseFields(WarehouseCatalog catalog){this.catalog=catalog;}
    public List<Map<String,Object>> transferFields(String kind,boolean writing){return catalog.fields(kind).stream().filter(f->!Boolean.TRUE.equals(f.get("readOnly"))&&!Boolean.TRUE.equals(f.get("readonly"))&&(!Boolean.TRUE.equals(f.get("financial"))||allowed(writing?"edit":"query"))&&(!Boolean.TRUE.equals(f.get("administratorOnly"))||SecurityUtils.isAdmin())).toList();}
    private boolean allowed(String action){return SecurityUtils.isAdmin()||SecurityUtils.hasPermi("warehouse:finance:"+action);}
    public void readableKind(String kind){
        if(Set.of("charges","invoices","billingRuns","adjustments","creditNotes","orderTiers","channelTiers","warehousePrices","locationPrices","recurring","invoiceTypes","courierPrices","collectionServices","courierUplifts","zeroRateCharges").contains(kind)&&!allowed("query"))throw WarehouseException.forbidden();
        if(Set.of("charges","invoices","billingRuns","adjustments","creditNotes").contains(kind))throw new WarehouseException(409,"warehouse_finance_workflow_required","Use the finance workflow API so every linked warehouse is checked / 请使用财务流程接口核对全部关联仓库");
    }
    public Map<String,Object> readable(String kind,Map<String,Object> row){
        var result=WarehouseJson.copy(row);
        if("returns".equals(kind)&&!allowed("query"))result.remove("rates");
        for(var field:catalog.fields(kind))if(Boolean.TRUE.equals(field.get("financial"))||"orderValue".equals(field.get("key"))){
            String key=WarehouseJson.text(field,"key");if(Boolean.TRUE.equals(field.get("financial"))&&!allowed("query")){result.remove(key);continue;}
            Object value=result.get(key);
            if("number".equals(field.get("type"))&&value instanceof Number)result.put(key,value instanceof java.math.BigDecimal d?d.toPlainString():value.toString());
        }
        return result;
    }
    public Collection<Map<String,Object>> catalog(){var entries=new ArrayList<Map<String,Object>>();for(var source:catalog.all()){
        var entity=WarehouseJson.copy(source);if(!SecurityUtils.isAdmin()){
            var fields=WarehouseJson.maps(entity.get("fields"));var hidden=new HashSet<Object>();fields.removeIf(f->{boolean hide=Boolean.TRUE.equals(f.get("financial"))&&!allowed("query")||Boolean.TRUE.equals(f.get("administratorOnly"));if(hide)hidden.add(f.get("key"));return hide;});
            WarehouseJson.maps(entity.get("columns")).removeIf(f->hidden.contains(f.get("key")));
        }entries.add(entity);
    }return entries;}
    public void writable(String kind,Map<String,Object> input){for(var f:catalog.fields(kind))if(input.containsKey(f.get("key"))&&(Boolean.TRUE.equals(f.get("financial"))&&!allowed("edit")||Boolean.TRUE.equals(f.get("administratorOnly"))&&!SecurityUtils.isAdmin()))throw WarehouseException.forbidden();}
}
