package com.ruoyi.warehouse.finance;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.warehouse.data.WarehouseRepository;
import com.ruoyi.warehouse.security.WarehouseScope;
import com.ruoyi.warehouse.support.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceRuleService {
    public static final Set<String> KINDS=Set.of("orderTiers","channelTiers","warehousePrices","recurring","invoiceTypes","locationPrices");
    private final WarehouseRepository records; private final WarehouseScope scope;
    public FinanceRuleService(WarehouseRepository records,WarehouseScope scope){this.records=records;this.scope=scope;}
    void permission(String action){if(!scope.admin()&&!SecurityUtils.hasPermi("warehouse:finance:"+action))throw WarehouseException.forbidden();}
    void kind(String kind){if(!KINDS.contains(kind))throw WarehouseException.invalid("Unsupported finance rule");}
    void access(String kind,Map<String,Object> row){if("invoiceTypes".equals(kind)||"locationPrices".equals(kind)&&WarehouseJson.empty(row.get("client"))){scope.requireAdministrator();return;}scope.assertRecord(kind,row);}
    public Map<String,Object> list(String kind,int page,int size,String client){permission("query");kind(kind);if("invoiceTypes".equals(kind))scope.requireAdministrator();return records.list(kind,page,size,"",client==null||client.isBlank()?Map.of():Map.of("client",client));}
    public Map<String,Object> get(String kind,String id){permission("query");kind(kind);var row=records.get(kind,id,false);access(kind,row);return row;}
    @Transactional public Map<String,Object> save(String kind,String id,Map<String,Object> input){
        permission("edit");kind(kind);String request=FinanceInput.id(input,"requestId");var clean=new LinkedHashMap<>(input);clean.remove("requestId");var data=normalize(kind,clean);access(kind,data);
        String hash=FinanceInput.hash(Arrays.asList(kind,id,input));var replay=claim(request,"FINANCE_RULE_SAVE",hash);if(replay!=null){access(kind,replay);return replay;}
        var old=id==null?null:records.get(kind,id,true);if(old!=null)access(kind,old);
        Long version=id==null?null:WarehouseJson.integer(input.get("_version"),"_version",1,Long.MAX_VALUE);
        if(old!=null&&!Objects.equals(old.get("_version"),version))throw WarehouseException.conflict("Rule changed; reload");
        var refs=new LinkedHashMap<String,String>();
        for(String[] ref:List.of(new String[]{"client","clients"},new String[]{"warehouse","warehouses"},new String[]{"product","products"})){
            String value=WarehouseJson.text(data,ref[0]);if(value.isEmpty())continue;var target=records.scoped(ref[1],value,true);if("product".equals(ref[0])&&!Objects.equals(data.get("client"),target.get("client")))throw WarehouseException.invalid("Product belongs to another client");refs.put(ref[0],value);
        }
        if("locationPrices".equals(kind)){var locationType=records.naturalCurrent("locationTypes","",WarehouseJson.text(data,"locationType").toLowerCase(Locale.ROOT));if(!Objects.equals(locationType.get("name"),data.get("locationType")))throw WarehouseException.invalid("Location type must match exactly");refs.put("locationType",WarehouseJson.text(locationType,"id"));}
        if("channelTiers".equals(kind))com.ruoyi.warehouse.data.SharedMasterReferences.channelPricing(records,data,old,refs);
        String rid=id==null?UUID.randomUUID().toString():id, natural=switch(kind){case "orderTiers"->"orders";case "channelTiers"->WarehouseJson.text(data,"channel");case "warehousePrices"->FinanceInput.hash(List.of(data.get("warehouse"),data.getOrDefault("product","")));case "invoiceTypes"->WarehouseJson.text(data,"name").toLowerCase(Locale.ROOT);case "locationPrices"->WarehouseJson.text(data,"locationType").toLowerCase(Locale.ROOT)+":"+data.get("frequency");default->rid;};
        var result=records.write(kind,rid,data,version,natural,WarehouseJson.text(data,"client"));records.replaceReferences(rid,refs);records.audit(id==null?"CREATE_FINANCE_RULE":"UPDATE_FINANCE_RULE",kind,rid,old,result);return complete(request,hash,result);
    }
    @Transactional public void delete(String kind,String id,long version,String request){permission("edit");kind(kind);FinanceInput.id(Map.of("requestId",request),"requestId");String hash=FinanceInput.hash(List.of(kind,id,version));var replay=claim(request,"FINANCE_RULE_DELETE",hash);if(replay!=null){access(kind,WarehouseJson.map(WarehouseJson.write(replay.get("record"))));return;}var old=records.get(kind,id,true);access(kind,old);if(((Number)old.get("_version")).longValue()!=version)throw WarehouseException.conflict("Rule changed");records.delete(kind,id,version);records.audit("DELETE_FINANCE_RULE",kind,id,old,null);complete(request,hash,Map.of("record",old,"deleted",true));}
    private Map<String,Object> claim(String request,String type,String hash){records.jdbc().update("INSERT INTO wh_operation_request(actor_id,request_id,operation_type,request_hash,response_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_id=request_id",scope.actor(),request,type,hash,"{}");return records.jdbc().queryForObject("SELECT operation_type,request_hash,response_json FROM wh_operation_request WHERE actor_id=? AND request_id=? FOR UPDATE",(rs,n)->{if(!type.equals(rs.getString(1))||!hash.equals(rs.getString(2)))throw WarehouseException.conflict("Request identity already used");var response=WarehouseJson.map(rs.getString(3));return response.isEmpty()?null:response;},scope.actor(),request);}
    private Map<String,Object> complete(String request,String hash,Map<String,Object> result){if(records.jdbc().update("UPDATE wh_operation_request SET response_json=? WHERE actor_id=? AND request_id=? AND request_hash=?",WarehouseJson.write(result),scope.actor(),request,hash)!=1)throw WarehouseException.conflict("Request journal changed");return result;}
    public static Map<String,Object> normalize(String kind,Map<String,Object> in){
        if(!KINDS.contains(kind))throw WarehouseException.invalid("Unsupported finance rule");
        String[] fields=switch(kind){case "orderTiers"->new String[]{"client","basis","tiers","minimumCharge","splitTiers","active","_version"};case "channelTiers"->new String[]{"client","channel","basis","tiers","minimumCharge","splitTiers","active","_version"};case "warehousePrices"->new String[]{"client","warehouse","product","firstPick","additionalPick","active","_version"};case "recurring"->new String[]{"client","warehouse","description","type","amount","quantity","frequency","notes","active","_version"};case "locationPrices"->new String[]{"client","locationType","frequency","cost","active","_version"};default->new String[]{"name","chargeType","vatRate","active","_version"};};
        FinanceInput.keys(in,fields);var d=new LinkedHashMap<String,Object>();d.put("active",FinanceInput.bool(in,"active",true));
        if("locationPrices".equals(kind))d.put("client",FinanceInput.text(in,"client",64,false));else if(!"invoiceTypes".equals(kind))d.put("client",FinanceInput.id(in,"client"));
        if(kind.endsWith("Tiers")){
            d.put("basis","orderTiers".equals(kind)?FinanceInput.option(in,"basis","Orders","Orders"):FinanceInput.option(in,"basis","Orders","Orders","Units"));
            if("channelTiers".equals(kind))d.put("channel",FinanceInput.text(in,"channel",100,true));
            d.put("minimumCharge",FinanceInput.number(in.getOrDefault("minimumCharge",0),"minimumCharge",false));d.put("splitTiers",FinanceInput.bool(in,"splitTiers",false));
            if(!(in.get("tiers") instanceof List<?> values)||values.isEmpty()||values.size()>100)throw WarehouseException.invalid("Add 1 to 100 tiers");
            var tiers=new ArrayList<Map<String,Object>>();var seen=new HashSet<Long>();for(Object value:values){if(!(value instanceof Map<?,?>))throw WarehouseException.invalid("Invalid tier");var t=WarehouseJson.map(WarehouseJson.write(value));FinanceInput.keys(t,"from","rate");long from=WarehouseJson.integer(t.get("from"),"from",1,9007199254740991L);if(!seen.add(from))throw WarehouseException.invalid("Duplicate tier boundary");tiers.add(Map.of("from",from,"rate",FinanceInput.number(t.get("rate"),"rate",false)));}tiers.sort(Comparator.comparingLong(t->((Number)t.get("from")).longValue()));if(((Number)tiers.get(0).get("from")).longValue()!=1)throw WarehouseException.invalid("Tiers must start at 1");d.put("tiers",tiers);
        }else if("warehousePrices".equals(kind)){
            d.put("warehouse",FinanceInput.id(in,"warehouse"));String product=FinanceInput.text(in,"product",64,false);if(!product.isEmpty())d.put("product",product);
            for(String key:List.of("firstPick","additionalPick"))if(in.containsKey(key)&&!"".equals(in.get(key)))d.put(key,FinanceInput.number(in.get(key),key,false));
        }else if("recurring".equals(kind)){
            String warehouse=FinanceInput.text(in,"warehouse",64,false);if(!warehouse.isEmpty())d.put("warehouse",warehouse);
            d.put("description",FinanceInput.text(in,"description",500,true));d.put("type",FinanceInput.option(in,"type","Additional","Additional","Storage","Rework","Collection"));d.put("amount",FinanceInput.number(in.get("amount"),"amount",false));d.put("quantity",FinanceInput.number(in.getOrDefault("quantity",1),"quantity",false));d.put("frequency",FinanceInput.option(in,"frequency","Daily","Daily","Weekly","Monthly"));d.put("notes",FinanceInput.text(in,"notes",5000,false));
        }else if("locationPrices".equals(kind)){
            d.put("locationType",FinanceInput.text(in,"locationType",100,true));d.put("frequency",FinanceInput.option(in,"frequency","Daily","Daily","Weekly","Monthly"));d.put("cost",FinanceInput.number(in.get("cost"),"cost",false));
        }else{
            d.put("name",FinanceInput.text(in,"name",100,true));d.put("chargeType",FinanceInput.option(in,"chargeType","Additional","Additional","Picking","Storage","Goods In","Collection","Rework"));BigDecimal vat=FinanceInput.number(in.getOrDefault("vatRate",0),"vatRate",false);if(vat.compareTo(new BigDecimal("100"))>0)throw WarehouseException.invalid("VAT must be at most 100%");d.put("vatRate",vat);
        }
        return d;
    }
}
