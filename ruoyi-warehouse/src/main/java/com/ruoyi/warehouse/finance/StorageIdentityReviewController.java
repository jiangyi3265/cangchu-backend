package com.ruoyi.warehouse.finance;
import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/warehouse/finance/storage-reviews")
public class StorageIdentityReviewController {
    private final StorageIdentityReviewService service;
    public StorageIdentityReviewController(StorageIdentityReviewService service){this.service=service;}
    @GetMapping("/context") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult context(@RequestParam String client,@RequestParam String from,@RequestParam String to){return data(service.context(client,from,to));}
    @GetMapping("/products") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult products(@RequestParam String client,@RequestParam(defaultValue="") String q){return data(service.products(client,q));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult get(@PathVariable String id){return data(service.get(id));}
    @PostMapping("/preview") @PreAuthorize("@ss.hasPermi('warehouse:finance:query')") public AjaxResult preview(@RequestBody Map<String,Object> input){return data(service.preview(input));}
    @PostMapping @PreAuthorize("@ss.hasPermi('warehouse:finance:edit')") public AjaxResult save(@RequestBody Map<String,Object> input){return data(service.save(input));}
    @PostMapping("/{id}/actions") @PreAuthorize("@ss.hasPermi('warehouse:finance:approveStorageReview')") public AjaxResult action(@PathVariable String id,@RequestBody Map<String,Object> input){return data(service.action(id,input));}
    private static AjaxResult data(Object value){return AjaxResult.success(FinanceDto.value(value));}
}
