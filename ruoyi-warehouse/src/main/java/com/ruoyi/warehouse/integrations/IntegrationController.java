package com.ruoyi.warehouse.integrations;

import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/warehouse/integrations")
public class IntegrationController {
    private final IntegrationService service;
    private final IntegrationAcceptanceService acceptance;
    public IntegrationController(IntegrationService service,IntegrationAcceptanceService acceptance){this.service=service;this.acceptance=acceptance;}
    @GetMapping("/capabilities") @PreAuthorize("@ss.hasPermi('warehouse:integrations:list')")
    public AjaxResult capabilities(){return AjaxResult.success(service.capabilities());}
    @GetMapping @PreAuthorize("@ss.hasPermi('warehouse:integrations:list')")
    public AjaxResult list(){return AjaxResult.success(service.connections());}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:integrations:query')")
    public AjaxResult get(@PathVariable String id){return AjaxResult.success(service.get(id));}
    @PostMapping @PreAuthorize("@ss.hasPermi('warehouse:integrations:configure')")
    public AjaxResult create(@RequestBody Map<String,Object> input){return AjaxResult.success(service.save(null,input));}
    @PutMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:integrations:configure')")
    public AjaxResult update(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.save(id,input));}
    @PostMapping("/{id}/pull") @PreAuthorize("@ss.hasPermi('warehouse:integrations:pull')")
    public AjaxResult pull(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.pull(id,input));}
    @GetMapping("/{id}/jobs") @PreAuthorize("@ss.hasPermi('warehouse:integrations:query')")
    public AjaxResult jobs(@PathVariable String id,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.jobs(id,pageNum,pageSize));}
    @GetMapping("/{id}/inbox") @PreAuthorize("@ss.hasPermi('warehouse:integrations:query')")
    public AjaxResult inbox(@PathVariable String id,@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String state){return AjaxResult.success(service.inbox(id,pageNum,pageSize,state));}
    @GetMapping("/{id}/inbox/{item}") @PreAuthorize("@ss.hasPermi('warehouse:integrations:query')")
    public AjaxResult detail(@PathVariable String id,@PathVariable String item){return AjaxResult.success(service.inboxDetail(id,item));}
    @GetMapping("/{id}/acceptance-settings") @PreAuthorize("@ss.hasPermi('warehouse:integrations:query')")
    public AjaxResult settings(@PathVariable String id,@RequestParam(defaultValue="")String item){return AjaxResult.success(acceptance.settings(id,item));}
    @PutMapping("/{id}/authority") @PreAuthorize("@ss.hasPermi('warehouse:integrations:authority')")
    public AjaxResult authority(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(acceptance.authority(id,input));}
    @PutMapping("/{id}/mapping") @PreAuthorize("@ss.hasPermi('warehouse:integrations:mapping')")
    public AjaxResult mapping(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(acceptance.mapping(id,input));}
    @PostMapping("/{id}/inbox/{item}/review") @PreAuthorize("@ss.hasPermi('warehouse:integrations:review')")
    public AjaxResult review(@PathVariable String id,@PathVariable String item){return AjaxResult.success(acceptance.review(id,item));}
    @PostMapping("/{id}/inbox/{item}/accept") @PreAuthorize("@ss.hasPermi('warehouse:integrations:accept')")
    public AjaxResult accept(@PathVariable String id,@PathVariable String item,@RequestBody Map<String,Object> input){return AjaxResult.success(acceptance.accept(id,item,input));}
    @PostMapping("/{id}/resolve") @PreAuthorize("@ss.hasPermi('warehouse:integrations:query')")
    public AjaxResult resolve(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(acceptance.resolve(id,input));}
    @ExceptionHandler(IntegrationFailure.class)
    public org.springframework.http.ResponseEntity<AjaxResult> failure(IntegrationFailure e){var result=AjaxResult.error(409,"Integration request could not proceed / 对接请求未能执行");result.put("error",e.code());result.put("data",Map.of("committed",false));return org.springframework.http.ResponseEntity.status(409).body(result);}
}
