package com.ruoyi.warehouse.inbound;

import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/warehouse/inbound")
public class InboundController {
    private final InboundService service;
    public InboundController(InboundService service){this.service=service;}
    @GetMapping("/list") @PreAuthorize("@ss.hasPermi('warehouse:asns:list')")
    public Map<String,Object> list(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String client,@RequestParam(defaultValue="")String warehouse){return service.list(pageNum,pageSize,keyword,client,warehouse);}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:asns:query')")
    public AjaxResult get(@PathVariable String id){return AjaxResult.success(service.get(id));}
    @PostMapping @PreAuthorize("@ss.hasPermi('warehouse:asns:add')")
    public AjaxResult create(@RequestBody Map<String,Object> input){return AjaxResult.success(service.save(null,input));}
    @PutMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:asns:edit')")
    public AjaxResult update(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.save(id,input));}
    @PostMapping("/{id}/receive") @PreAuthorize("@ss.hasPermi('warehouse:asns:receive')")
    public AjaxResult receive(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.receive(id,input));}
    @PostMapping("/{id}/close") @PreAuthorize("@ss.hasPermi('warehouse:asns:close')")
    public AjaxResult close(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.close(id,input));}
    @PostMapping("/{id}/cancel") @PreAuthorize("@ss.hasPermi('warehouse:asns:cancel')")
    public AjaxResult cancel(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.cancel(id,input));}
    @PostMapping("/{id}/lateSupplement") @PreAuthorize("@ss.hasPermi('warehouse:asns:lateSupplement')")
    public AjaxResult lateSupplement(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.lateSupplement(id,input));}
}
