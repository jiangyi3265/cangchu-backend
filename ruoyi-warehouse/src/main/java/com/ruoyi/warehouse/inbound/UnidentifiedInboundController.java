package com.ruoyi.warehouse.inbound;
import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/warehouse/inbound/{id}/unknown")
public class UnidentifiedInboundController {
    private final UnidentifiedInboundService service;
    public UnidentifiedInboundController(UnidentifiedInboundService service){this.service=service;}
    @PostMapping("/{action}") @PreAuthorize("@ss.hasPermi('warehouse:asns:' + #action)")
    public AjaxResult action(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> input){return AjaxResult.success(service.action(id,action,input));}
}
