package com.ruoyi.warehouse.inbound;
import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/warehouse/inbound/{asn}/corrections")
public class ReceiptCorrectionController {
    private final ReceiptCorrectionService service;
    public ReceiptCorrectionController(ReceiptCorrectionService service){this.service=service;}
    @GetMapping("/context") @PreAuthorize("@ss.hasPermi('warehouse:asns:query')") public AjaxResult context(@PathVariable String asn,@RequestParam String receiptLine){return AjaxResult.success(service.context(asn,receiptLine));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:asns:query')") public AjaxResult get(@PathVariable String asn,@PathVariable String id){return AjaxResult.success(service.detail(asn,id));}
    @PostMapping("/preview") @PreAuthorize("@ss.hasPermi('warehouse:asns:approveCorrection')") public AjaxResult preview(@PathVariable String asn,@RequestBody Map<String,Object> input){return AjaxResult.success(service.preview(asn,input));}
    @PostMapping @PreAuthorize("@ss.hasPermi('warehouse:asns:approveCorrection')") public AjaxResult approve(@PathVariable String asn,@RequestBody Map<String,Object> input){return AjaxResult.success(service.approve(asn,input));}
    @PostMapping("/{id}/execute") @PreAuthorize("@ss.hasPermi('warehouse:asns:executeCorrection')") public AjaxResult execute(@PathVariable String asn,@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.action(asn,id,"execute",input));}
    @PostMapping("/{id}/cancel") @PreAuthorize("@ss.hasPermi('warehouse:asns:cancelCorrection')") public AjaxResult cancel(@PathVariable String asn,@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.action(asn,id,"cancel",input));}
}
