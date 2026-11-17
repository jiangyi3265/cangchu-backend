package com.ruoyi.warehouse.returns;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.warehouse.security.WarehouseFields;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/warehouse/returns")
public class ReturnController {
    private final ReturnService service;
    private final WarehouseFields fields;
    public ReturnController(ReturnService service,WarehouseFields fields){this.service=service;this.fields=fields;}
    @GetMapping("/list") @PreAuthorize("@ss.hasPermi('warehouse:returns:list')")
    public Map<String,Object> list(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String client,@RequestParam(defaultValue="") String warehouse){var result=new LinkedHashMap<>(service.list(pageNum,pageSize,keyword,client,warehouse));result.put("rows",WarehouseJson.maps(result.get("rows")).stream().map(r->fields.readable("returns",r)).toList());return result;}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:returns:query')")
    public AjaxResult get(@PathVariable String id){return AjaxResult.success(fields.readable("returns",service.get(id)));}
    @PostMapping @PreAuthorize("@ss.hasPermi('warehouse:returns:add')")
    public AjaxResult create(@RequestBody Map<String,Object> input){return AjaxResult.success(fields.readable("returns",service.create(input)));}
    @PostMapping("/{id}/actions") @PreAuthorize("@ss.hasPermi('warehouse:returns:'+#input['action'])")
    public AjaxResult action(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(fields.readable("returns",service.action(id,input)));}
}
