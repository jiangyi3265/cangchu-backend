package com.ruoyi.warehouse.orders;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.warehouse.security.WarehouseFields;
import com.ruoyi.warehouse.support.WarehouseJson;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/warehouse/orders")
public class OrderController {
    private final OrderService service;
    private final WarehouseFields fields;
    public OrderController(OrderService service, WarehouseFields fields) { this.service = service; this.fields = fields; }
    @GetMapping("/list") @PreAuthorize("@ss.hasPermi('warehouse:orders:list')")
    public Map<String, Object> list(@RequestParam(defaultValue="1") int pageNum, @RequestParam(defaultValue="20") int pageSize, @RequestParam(defaultValue="") String client, @RequestParam(defaultValue="") String warehouse, @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String keyword) {
        var result = new LinkedHashMap<>(service.list(pageNum, pageSize, client, warehouse, status, keyword)); result.put("rows", WarehouseJson.maps(result.get("rows")).stream().map(row -> fields.readable("orders", row)).toList()); return result;
    }
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:orders:query')")
    public AjaxResult get(@PathVariable String id) { return AjaxResult.success(fields.readable("orders", service.get(id))); }
    @PostMapping @PreAuthorize("@ss.hasPermi('warehouse:orders:add')")
    public AjaxResult create(@RequestBody Map<String, Object> input) { fields.writable("orders", input); return AjaxResult.success(fields.readable("orders", service.save(null, input))); }
    @PutMapping("/{id}") @PreAuthorize("@ss.hasPermi('warehouse:orders:edit')")
    public AjaxResult update(@PathVariable String id, @RequestBody Map<String, Object> input) { fields.writable("orders", input); return AjaxResult.success(fields.readable("orders", service.save(id, input))); }
    @PostMapping("/{id}/actions") @PreAuthorize("@ss.hasPermi('warehouse:orders:'+#input['action'])")
    public AjaxResult action(@PathVariable String id, @RequestBody Map<String, Object> input) { return AjaxResult.success(fields.readable("orders", service.action(id, input))); }
    @PostMapping("/{id}/work") @PreAuthorize("@ss.hasPermi('warehouse:orders:'+#input['action'])")
    public AjaxResult work(@PathVariable String id, @RequestBody Map<String, Object> input) { return AjaxResult.success(fields.readable("orders", service.workAction(id, input))); }
}
