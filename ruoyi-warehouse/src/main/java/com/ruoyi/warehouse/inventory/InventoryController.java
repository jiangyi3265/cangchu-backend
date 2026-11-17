package com.ruoyi.warehouse.inventory;

import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/warehouse/inventory")
public class InventoryController {
    private final InventoryService service;
    public InventoryController(InventoryService service) { this.service = service; }

    @GetMapping("/list")
    @PreAuthorize("@ss.hasPermi('warehouse:inventory:list')")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int pageNum,
        @RequestParam(defaultValue = "20") int pageSize, @RequestParam(defaultValue = "") String client,
        @RequestParam(defaultValue = "") String product, @RequestParam(defaultValue = "") String warehouse,
        @RequestParam(defaultValue = "") String keyword) {
        return service.list(pageNum, pageSize, client, product, warehouse, keyword);
    }

    @PostMapping("/movements")
    @PreAuthorize("@ss.hasPermi('warehouse:inventory:move')")
    public AjaxResult move(@RequestBody Map<String, Object> input) { return AjaxResult.success(service.move(input)); }
}
