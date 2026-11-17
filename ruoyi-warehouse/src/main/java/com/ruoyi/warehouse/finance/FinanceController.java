package com.ruoyi.warehouse.finance;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/warehouse/finance")
public class FinanceController {
    private final FinanceRuleService rules;private final FinanceWorkflowService workflow;
    public FinanceController(FinanceRuleService rules,FinanceWorkflowService workflow){this.rules=rules;this.workflow=workflow;}
    @GetMapping("/rules/{kind}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public Map<String,Object> rules(@PathVariable String kind,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String client){return FinanceDto.map(rules.list(kind,pageNum,pageSize,client));}
    @GetMapping("/rules/{kind}/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult rule(@PathVariable String kind,@PathVariable String id){return data(rules.get(kind,id));}
    @PostMapping("/rules/{kind}") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult create(@PathVariable String kind,@RequestBody Map<String,Object> data){return data(rules.save(kind,null,data));}
    @PutMapping("/rules/{kind}/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult update(@PathVariable String kind,@PathVariable String id,@RequestBody Map<String,Object> data){return data(rules.save(kind,id,data));}
    @DeleteMapping("/rules/{kind}/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult delete(@PathVariable String kind,@PathVariable String id,@RequestParam long version,@RequestParam String requestId){rules.delete(kind,id,version,requestId);return AjaxResult.success();}
    @GetMapping("/charges") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public Map<String,Object> charges(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String client){return FinanceDto.map(workflow.list("charges",pageNum,pageSize,client));}
    @PostMapping("/charges") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult charge(@RequestBody Map<String,Object> input){return data(workflow.manualCharge(input));}
    @GetMapping("/invoices") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public Map<String,Object> invoices(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String client){return FinanceDto.map(workflow.list("invoices",pageNum,pageSize,client));}
    @GetMapping("/invoices/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult invoice(@PathVariable String id){return data(workflow.invoice(id));}
    @PostMapping("/invoices") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult draft(@RequestBody Map<String,Object> input){return data(workflow.draft(input));}
    @PostMapping("/invoices/{id}/actions") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult action(@PathVariable String id,@RequestBody Map<String,Object> input){return data(workflow.invoiceAction(id,input));}
    @PostMapping("/billing/preview") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult preview(@RequestBody Map<String,Object> input){return data(workflow.preview(input));}
    @PostMapping("/billing/commit") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult commit(@RequestBody Map<String,Object> input){return data(workflow.commit(input));}
    @GetMapping("/billing/runs") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public Map<String,Object> runs(@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String client){return FinanceDto.map(workflow.list("billingRuns",pageNum,pageSize,client));}
    @GetMapping("/billing/runs/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult run(@PathVariable String id){return data(workflow.run(id));}
    private static AjaxResult data(Object value){return AjaxResult.success(FinanceDto.value(value));}
}
