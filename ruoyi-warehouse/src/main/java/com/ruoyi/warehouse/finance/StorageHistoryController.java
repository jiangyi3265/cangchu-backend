package com.ruoyi.warehouse.finance;
import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/warehouse/finance/storage-history")
public class StorageHistoryController {
    private final StorageHistoryService service;
    public StorageHistoryController(StorageHistoryService service){this.service=service;}
    @GetMapping("/status") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public AjaxResult status(){return AjaxResult.success(FinanceDto.value(service.status()));}
    @PostMapping("/start") @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')")
    public AjaxResult start(@RequestBody Map<String,Object> input){return AjaxResult.success(FinanceDto.value(service.start(input)));}
    @GetMapping("/day") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')")
    public AjaxResult day(@RequestParam String client,@RequestParam String date){return AjaxResult.success(FinanceDto.value(service.day(client,date)));}
}
