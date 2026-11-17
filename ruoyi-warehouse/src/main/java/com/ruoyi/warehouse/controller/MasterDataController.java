package com.ruoyi.warehouse.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.warehouse.catalog.WarehouseCatalog;
import com.ruoyi.warehouse.data.*;
import com.ruoyi.warehouse.support.WarehouseJson;
import com.ruoyi.warehouse.security.WarehouseFields;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/warehouse")
public class MasterDataController {
    private final WarehouseCatalog catalog;private final WarehouseRepository records;private final MasterDataService service;private final WarehouseFields fieldAccess;
    public MasterDataController(WarehouseCatalog catalog,WarehouseRepository records,MasterDataService service,WarehouseFields fieldAccess){this.catalog=catalog;this.records=records;this.service=service;this.fieldAccess=fieldAccess;}
    @GetMapping("/catalog") @PreAuthorize("@ss.hasPermi('warehouse:catalog:list')")
    public AjaxResult catalog(){return AjaxResult.success(Map.of("entities",fieldAccess.catalog(),"capabilities",catalog.capabilities()));}
    @GetMapping("/capabilities") @PreAuthorize("@ss.hasPermi('warehouse:catalog:list')")
    public AjaxResult capabilities(){return AjaxResult.success(catalog.capabilities());}
    @GetMapping("/records/{kind}/list") @PreAuthorize("@ss.hasPermi('warehouse:'+#kind+':list')")
    public Map<String,Object> list(@PathVariable String kind,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String keyword){catalog.entity(kind);fieldAccess.readableKind(kind);var result=new LinkedHashMap<>(records.list(kind,pageNum,pageSize,keyword));result.put("rows",WarehouseJson.maps(result.get("rows")).stream().map(r->fieldAccess.readable(kind,r)).toList());return result;}
    @GetMapping("/records/{kind}/{id}") @PreAuthorize("@ss.hasPermi('warehouse:'+#kind+':query')")
    public AjaxResult get(@PathVariable String kind,@PathVariable String id){catalog.entity(kind);fieldAccess.readableKind(kind);return AjaxResult.success(fieldAccess.readable(kind,records.scoped(kind,id,false)));}
    @PostMapping("/records/{kind}") @PreAuthorize("@ss.hasPermi('warehouse:'+#kind+':add')")
    public AjaxResult create(@PathVariable String kind,@RequestBody Map<String,Object> data){return AjaxResult.success(fieldAccess.readable(kind,service.save(kind,null,data)));}
    @PutMapping("/records/{kind}/{id}") @PreAuthorize("@ss.hasPermi('warehouse:'+#kind+':edit')")
    public AjaxResult update(@PathVariable String kind,@PathVariable String id,@RequestBody Map<String,Object> data){return AjaxResult.success(fieldAccess.readable(kind,service.save(kind,id,data)));}
    @DeleteMapping("/records/{kind}/{id}") @PreAuthorize("@ss.hasPermi('warehouse:'+#kind+':remove')")
    public AjaxResult delete(@PathVariable String kind,@PathVariable String id,@RequestParam long version){service.delete(kind,id,version);return AjaxResult.success();}
    @GetMapping("/lookups/{kind}") @PreAuthorize("@ss.hasPermi('warehouse:'+#kind+':list')")
    public AjaxResult lookup(@PathVariable String kind,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="200") int limit,@RequestParam(defaultValue="") String client,@RequestParam(defaultValue="") String warehouse){
        catalog.entity(kind);fieldAccess.readableKind(kind);
        if(!client.isEmpty())records.scoped("clients",client,false);if(!warehouse.isEmpty())records.scoped("warehouses",warehouse,false);
        var data=WarehouseJson.maps(records.list(kind,1,limit,q,Map.of("client",client,"warehouse",warehouse)).get("rows"));
        return AjaxResult.success(data.stream().map(r->{Map<String,Object> option=new LinkedHashMap<>(Map.of("id",r.get("id"),"label",List.of("shortName","sku","name","reference").stream().map(k->WarehouseJson.text(r,k)).filter(x->!x.isEmpty()).findFirst().orElse(WarehouseJson.text(r,"id")),"client",WarehouseJson.text(r,"client"),"warehouse",WarehouseJson.text(r,"warehouse")));if(Set.of("locations","suppliers","channels").contains(kind))option.put("active",!Boolean.FALSE.equals(r.get("active"))&&!"N".equals(r.get("active")));if("locations".equals(kind)){option.put("type",WarehouseJson.text(r,"type"));option.put("custodyOnly",WarehouseJson.enabled(r.get("custodyOnly")));option.put("active",!Boolean.FALSE.equals(r.get("active"))&&!"N".equals(r.get("active")));}return option;}).toList());
    }
    @GetMapping("/dashboard") @PreAuthorize("@ss.hasPermi('warehouse:dashboard:query')")
    public AjaxResult dashboard(){var counts=new LinkedHashMap<String,Long>();for(String kind:List.of("clients","products","warehouses","locations","asns","orders"))counts.put(kind,records.count(kind));return AjaxResult.success(Map.of("counts",counts,"capabilities",catalog.capabilities()));}
}
