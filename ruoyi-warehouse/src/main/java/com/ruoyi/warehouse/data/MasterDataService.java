package com.ruoyi.warehouse.data;

import com.ruoyi.warehouse.catalog.WarehouseCatalog;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.security.WarehouseFields;
import com.ruoyi.warehouse.support.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;

@Service
public class MasterDataService {
    private final WarehouseCatalog catalog;
    private final WarehouseRepository records;
    private final WarehouseScope scope;
    private final WarehouseFields fieldAccess;
    public MasterDataService(WarehouseCatalog catalog,WarehouseRepository records,WarehouseScope scope,WarehouseFields fieldAccess){this.catalog=catalog;this.records=records;this.scope=scope;this.fieldAccess=fieldAccess;}
    @Transactional
    public Map<String,Object> save(String kind,String id,Map<String,Object> input){
        return saveInTransaction(kind,id,input);
    }
    // Batch previews use savepoints in an owning transaction that always rolls back.
    // Keeping this internal avoids an inner proxy marking the whole preview rollback-only.
    Map<String,Object> saveInTransaction(String kind,String id,Map<String,Object> input){
        catalog.editable(kind);if(input==null)throw WarehouseException.invalid("Record is required");
        fieldAccess.writable(kind,input);
        if(WarehouseJson.write(input).length()>262144)throw WarehouseException.invalid("Record exceeds size limit");
        var old=id==null?null:records.scoped(kind,id,true);
        Long version=id==null?null:WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE);
        if(old!=null&&version.longValue()!=((Number)old.get("_version")).longValue())throw WarehouseException.conflict("Record changed; reload before saving / 数据已更新，请刷新后重试");
        var fields=catalog.fields(kind);var allowed=new HashSet<String>();for(var f:fields)allowed.add(WarehouseJson.text(f,"key"));allowed.add("_version");
        for(String key:input.keySet())if(!allowed.contains(key))throw WarehouseException.invalid("Unknown or protected field: "+key);
        var data=new LinkedHashMap<String,Object>();
        for(var f:fields){String key=WarehouseJson.text(f,"key");Object value=input.containsKey(key)?input.get(key):old!=null?old.get(key):f.get("default");
            if(Boolean.TRUE.equals(f.get("required"))&&WarehouseJson.empty(value))throw WarehouseException.invalid(key+" is required / 必填字段缺失");
            Object validated=validateField(f,value);if(validated!=null)data.put(key,validated);
        }
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Master data requires an owning transaction");
        if(Set.of("suppliers","channels").contains(kind)){scope.requireAdministrator();if(!(data.get("active") instanceof Boolean))throw WarehouseException.invalid("Choose an explicit active state / 请明确启用或停用状态");}
        if("channels".equals(kind)){String name=WarehouseJson.text(data,"name");if(name.length()>100||"manual".equalsIgnoreCase(name))throw WarehouseException.invalid("Channel name must be at most 100 characters; Manual is reserved / 渠道名最长 100 字符，Manual 为内置渠道");if(old!=null&&!Objects.equals(name,old.get("name")))SharedMasterReferences.protectChannelIdentity(records,old);}
        if(id==null&&List.of("clients","warehouses","locationTypes","couriers","channels","categories","suppliers","zones","invoiceTypes","reworkItems").contains(kind))scope.requireAdministrator();
        if(old!=null)scope.assertRecord(kind,old);
        if(data.containsKey("client")||data.containsKey("warehouse"))scope.assertRecord(kind,data);
        var references=new LinkedHashMap<String,String>();
        for(var f:fields){String key=WarehouseJson.text(f,"key"),source=WarehouseJson.text(f,"source");Object value=data.get(key);
            if("ref".equals(f.get("type"))&&!WarehouseJson.empty(value)){
                var target=records.get(source,String.valueOf(value),true);scope.assertRecord(source,target);
                String filter=WarehouseJson.text(f,"filterBy");if(!filter.isEmpty()&&data.containsKey(filter)&&!Objects.equals(data.get(filter),target.get(filter)))throw WarehouseException.invalid(key+" belongs to another "+filter);
                references.put(key,String.valueOf(value));
            }
        }
        if("products".equals(kind))validateProduct(data,old,id,references);
        if("locations".equals(kind))validateLocation(data,old,id,references);
        if("locationTypes".equals(kind)&&old!=null)validateTypeCapacity(data,old);
        if("locationTypes".equals(kind)&&old!=null&&!Objects.equals(old.get("name"),data.get("name"))&&records.all("locations").stream().anyMatch(l->Objects.equals(l.get("type"),old.get("name"))))throw WarehouseException.conflict("Location type is in use / 库位类型正在使用");
        if("clients".equals(kind)&&!WarehouseJson.enabled(data.get("allowDuplicateBarcodes")))validateClientBarcodes(id);
        String recordId=id==null?UUID.randomUUID().toString():id;String natural=naturalKey(kind,data,recordId);if(natural.length()>191)throw WarehouseException.invalid("Natural key is too long");
        String scopeKey="products".equals(kind)?WarehouseJson.text(data,"client"):"locations".equals(kind)?WarehouseJson.text(data,"warehouse"):"";
        var result=records.write(kind,recordId,data,version,natural,scopeKey);records.replaceReferences(recordId,references);records.audit(id==null?"CREATE":"UPDATE",kind,recordId,old,result);return result;
    }
    public Object validateField(Map<String,Object> field,Object value){
        String type=WarehouseJson.text(field,"type"),key=WarehouseJson.text(field,"key");if("expiryPolicy".equals(key)&&WarehouseJson.empty(value))throw WarehouseException.invalid("Expiry policy must be explicit / 有效期策略不能为空");if(WarehouseJson.empty(value))return value;
        if("number".equals(type)){
            BigDecimal number;try{if(!(value instanceof Number||value instanceof String))throw new NumberFormatException();number=new BigDecimal(value.toString());}catch(Exception e){throw WarehouseException.invalid(key+" must be a finite number");}
            BigDecimal min=new BigDecimal(Objects.toString(field.getOrDefault("min",0)));if(number.compareTo(min)<0||field.containsKey("max")&&number.compareTo(new BigDecimal(field.get("max").toString()))>0)throw WarehouseException.invalid(key+" is outside its allowed range");
            if("1".equals(Objects.toString(field.get("step"))))WarehouseJson.integer(number,key,0,9007199254740991L);
            if(number.precision()>24||number.scale()>9||(long)number.precision()-number.scale()>24)throw WarehouseException.invalid(key+" has too much precision");return number;
        }
        if("toggle".equals(type)){
            if(value instanceof Boolean)return value;String s=value.toString();if(Set.of("true","Y","1").contains(s))return true;if(Set.of("false","N","0").contains(s))return false;throw WarehouseException.invalid(key+" must be true or false");
        }
        if(!(value instanceof String s))throw WarehouseException.invalid(key+" must be text");
        int max=field.containsKey("maxLength")?Integer.parseInt(field.get("maxLength").toString()):5000;if(s.length()>max||s.indexOf('\0')>=0)throw WarehouseException.invalid(key+" exceeds text limits");
        if("date".equals(type)){try{if(!s.matches("\\d{4}-\\d{2}-\\d{2}"))throw new IllegalArgumentException();LocalDate.parse(s,DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT));}catch(Exception e){throw WarehouseException.invalid(key+" is not a calendar date");}}
        if("select".equals(type)&&!field.containsKey("lookupSource")){
            Object options=field.get("options");if(options instanceof List<?> list&&!list.isEmpty()&&list.stream().noneMatch(o->Objects.equals(o instanceof Map<?,?> m?m.get("value"):o,s)))throw WarehouseException.invalid(key+" is not a supported option");
        }
        if("email".equals(type)&&!s.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))throw WarehouseException.invalid(key+" is not a valid email address");
        return s.strip();
    }
    private void validateProduct(Map<String,Object> data,Map<String,Object> old,String id,Map<String,String> refs){
        String un=WarehouseJson.text(data,"hazardousUnNumber");if(!un.isEmpty()){
            if(!un.matches("\\d{4}")||WarehouseJson.empty(data.get("properShippingName")))throw WarehouseException.invalid("Hazardous goods require a four-digit UN and proper shipping name / 危险品须填写四位UN编号及运输名称");
            data.put("fullProperShippingCode",String.join(", ",List.of("hazardousUnNumber","properShippingName","hazardousClass","packingCode","tunnelCode").stream().map(k->WarehouseJson.text(data,k)).filter(x->!x.isEmpty()).toList()));
        }
        var client=records.get("clients",WarehouseJson.text(data,"client"),true);
        if(old==null)for(String[] tracking:List.of(new String[]{"hasBatch","trackBatch"},new String[]{"hasSerial","trackSerial"},new String[]{"hasExpiry","trackExpiry"}))if(WarehouseJson.enabled(client.get(tracking[1])))data.put(tracking[0],true);
        if(old!=null&&records.inventoryHistory(id)){
            if(!Objects.equals(old.get("client"),data.get("client")))throw WarehouseException.conflict("A product with inventory history cannot change client");
            if(records.stockExists(id)&&List.of("hasBatch","hasSerial","hasExpiry").stream().anyMatch(k->!Objects.equals(old.get(k),data.get(k))))throw WarehouseException.conflict("Tracking cannot change while product stock exists");
        }
        if(!WarehouseJson.enabled(client.get("allowDuplicateBarcodes"))){var codes=new HashSet<String>();for(String key:List.of("barcode","upc"))if(!WarehouseJson.empty(data.get(key)))codes.add(WarehouseJson.text(data,key));
            for(var p:records.clientRecordsCurrent("products",WarehouseJson.text(data,"client")))if(!Objects.equals(p.get("id"),id))for(String key:List.of("barcode","upc"))if(codes.contains(WarehouseJson.text(p,key)))throw WarehouseException.conflict("Duplicate product barcode for this client");}
    }
    private void validateClientBarcodes(String client){if(client==null)return;var used=new HashMap<String,String>();for(var p:records.clientRecordsCurrent("products",client))for(String field:List.of("barcode","upc")){String code=WarehouseJson.text(p,field);if(code.isEmpty())continue;String prior=used.putIfAbsent(code,WarehouseJson.text(p,"id"));if(prior!=null&&!prior.equals(p.get("id")))throw WarehouseException.conflict("Existing duplicate barcodes must be resolved first");}}
    private void validateLocation(Map<String,Object> data,Map<String,Object> old,String id,Map<String,String> refs){
        if(old!=null&&List.of("warehouse","client","type","custodyOnly").stream().anyMatch(key->!Objects.equals(old.get(key),data.get(key)))&&!records.jdbc().queryForList("SELECT stocktake_id FROM wh_stocktake_lock WHERE location_id=? FOR UPDATE",String.class,id).isEmpty())throw WarehouseException.conflict("Location identity cannot change during a stocktake / 盘点期间不能更改库位归属或用途");
        var type=records.naturalCurrent("locationTypes","",WarehouseJson.text(data,"type").toLowerCase(Locale.ROOT));
        if(!Objects.equals(type.get("name"),data.get("type")))throw WarehouseException.invalid("Location type name must match exactly");
        if(Boolean.FALSE.equals(type.get("active")))throw WarehouseException.invalid("Location type is inactive");refs.put("locationType",WarehouseJson.text(type,"id"));
        if(WarehouseJson.enabled(data.get("custodyOnly"))&&!"Quarantine".equals(data.get("type")))throw WarehouseException.invalid("Custody requires a Quarantine location");
        if(old!=null&&records.inventoryHistory(id)&&!Objects.equals(old.get("warehouse"),data.get("warehouse")))throw WarehouseException.conflict("Location with inventory history cannot change warehouse");
        if(old!=null&&records.stockExists(id)&&!Objects.equals(old.get("custodyOnly"),data.get("custodyOnly")))throw WarehouseException.conflict("Custody classification cannot change while stock exists");
        if(old!=null&&records.stockExists(id)&&(!Objects.equals(old.get("client"),data.get("client"))||("Quarantine".equals(old.get("type"))||"Quarantine".equals(data.get("type")))&&!Objects.equals(old.get("type"),data.get("type"))))throw WarehouseException.conflict("Stock ownership and quarantine classification require an audited stock workflow / 有库存时不能更改客户归属或隔离用途");
        if(old!=null)checkCapacity(id,capacity(data,type));
    }
    private long capacity(Map<String,Object> location,Map<String,Object> type){Object own=location.get("maxUnits");long value=WarehouseJson.empty(own)?0:WarehouseJson.integer(own,"capacity",0,9007199254740991L);return value>0?value:WarehouseJson.integer(type.getOrDefault("maxUnits",0),"capacity",0,9007199254740991L);}
    private void checkCapacity(String location,long maximum){if(maximum==0)return;long occupied=com.ruoyi.warehouse.inventory.UnidentifiedOccupancy.current(records.jdbc(),location);for(Long quantity:records.jdbc().queryForList("SELECT on_hand FROM wh_inventory WHERE location_id=? FOR UPDATE",Long.class,location))occupied=Math.addExact(occupied,quantity);if(occupied>maximum)throw WarehouseException.conflict("Location capacity is below current stock / 库位容量不能低于当前库存与未识别实物");}
    private void validateTypeCapacity(Map<String,Object> data,Map<String,Object> old){
        var ids=records.jdbc().queryForList("SELECT id FROM wh_record WHERE kind='locations' AND JSON_UNQUOTE(JSON_EXTRACT(business_json,'$.type'))=? ORDER BY id FOR UPDATE",String.class,old.get("name"));
        for(String location:ids)checkCapacity(location,capacity(records.get("locations",location,true),data));
    }
    private String naturalKey(String kind,Map<String,Object> data,String fallback){return switch(kind){case "clients"->WarehouseJson.text(data,"shortName").toLowerCase(Locale.ROOT);case "products"->WarehouseJson.text(data,"sku").toLowerCase(Locale.ROOT);case "locationTypes"->WarehouseJson.text(data,"name").toLowerCase(Locale.ROOT);case "locations"->WarehouseJson.text(data,"name");case "warehouses","channels","categories","suppliers","zones","invoiceTypes"->WarehouseJson.text(data,"name").toLowerCase(Locale.ROOT);default->fallback;};}
    @Transactional public void delete(String kind,String id,long version){catalog.editable(kind);if(Set.of("suppliers","channels").contains(kind))scope.requireAdministrator();var row=records.scoped(kind,id,true);if("channels".equals(kind))SharedMasterReferences.protectChannelIdentity(records,row);if(((Number)row.get("_version")).longValue()!=version)throw WarehouseException.conflict("Record changed / 数据已更新");if(records.inventoryHistory(id))throw WarehouseException.conflict("Inventory history cannot be deleted / 存在库存历史，不能删除");records.delete(kind,id,version);records.audit("DELETE",kind,id,row,null);}
}
