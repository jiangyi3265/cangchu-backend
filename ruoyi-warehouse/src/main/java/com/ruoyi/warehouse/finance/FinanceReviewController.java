package com.ruoyi.warehouse.finance;
import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/warehouse/finance/review-cases")
public class FinanceReviewController {
    private final FinanceReviewService service;
    public FinanceReviewController(FinanceReviewService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public Object list(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize,@RequestParam(defaultValue="")String client,@RequestParam(defaultValue="OPEN")String status){return FinanceDto.value(service.list(pageNum,pageSize,client,status));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult get(@PathVariable String id){return AjaxResult.success(FinanceDto.value(service.get(id)));}
    @PostMapping("/{id}/actions") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult action(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(FinanceDto.value(service.action(id,input)));}
}
