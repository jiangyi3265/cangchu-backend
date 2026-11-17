package com.ruoyi.warehouse.finance;

import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/warehouse/finance")
public class FinanceAdjustmentController {
    private final FinanceAdjustmentService service;private final AdjustmentPolicyService policy;
    public FinanceAdjustmentController(FinanceAdjustmentService service,AdjustmentPolicyService policy){this.service=service;this.policy=policy;}
    @GetMapping("/adjustments") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public Map<String,Object> list(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String client){return FinanceDto.map(service.list("adjustments",pageNum,pageSize,client));}
    @GetMapping("/credit-notes") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public Map<String,Object> notes(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String client){return FinanceDto.map(service.list("creditNotes",pageNum,pageSize,client));}
    @GetMapping("/adjustments/source/{charge}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public AjaxResult source(@PathVariable String charge){return data(service.source(charge));}
    @GetMapping("/adjustments/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public AjaxResult get(@PathVariable String id){return data(service.get(id));}
    @PostMapping("/adjustments") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')")
    public AjaxResult create(@RequestBody Map<String,Object> input){return data(service.create(input));}
    @PostMapping("/adjustments/{id}/preview") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public AjaxResult preview(@PathVariable String id,@RequestBody Map<String,Object> input){return data(service.preview(id,input));}
    @PostMapping("/adjustments/{id}/actions") @PreAuthorize("@ss.hasAnyPermi('warehouse:finance:edit,warehouse:finance:approveAdjustment,warehouse:finance:executeAdjustment')")
    public AjaxResult action(@PathVariable String id,@RequestBody Map<String,Object> input){return data(service.action(id,input));}
    @GetMapping("/credit-notes/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public AjaxResult note(@PathVariable String id){return data(service.creditNote(id));}
    @GetMapping("/adjustment-policy") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public AjaxResult policy(){return data(policy.get());}
    @PostMapping("/adjustment-policy") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')")
    public AjaxResult policy(@RequestBody Map<String,Object> input){return data(policy.save(input));}
    private static AjaxResult data(Object value){return AjaxResult.success(FinanceDto.value(value));}
}
