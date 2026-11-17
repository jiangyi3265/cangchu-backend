package com.ruoyi.warehouse.stocktake;

import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/warehouse/stocktakes")
public class StocktakeController {
    private final StocktakeService service;
    public StocktakeController(StocktakeService service) {this.service=service;}
    @GetMapping("/list") @PreAuthorize("@ss.hasPermi('warehouse:stocktakes:list')")
    public Map<String,Object> list(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String client,@RequestParam(defaultValue="") String warehouse) {return service.list(pageNum,pageSize,keyword,client,warehouse);}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:stocktakes:query')")
    public AjaxResult get(@PathVariable String id) {return AjaxResult.success(service.get(id));}
    @PostMapping @PreAuthorize("@ss.hasPermi('warehouse:stocktakes:add')")
    public AjaxResult create(@RequestBody Map<String,Object> input) {return AjaxResult.success(service.create(input));}
    @PostMapping("/{id}/actions") @PreAuthorize("@ss.hasPermi('warehouse:stocktakes:'+#input['action'])")
    public AjaxResult action(@PathVariable String id,@RequestBody Map<String,Object> input) {return AjaxResult.success(service.action(id,input));}
}
