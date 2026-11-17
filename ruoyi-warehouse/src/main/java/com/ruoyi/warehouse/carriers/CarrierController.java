package com.ruoyi.warehouse.carriers;

import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/warehouse/carriers")
public class CarrierController {
    private final CarrierService service;
    public CarrierController(CarrierService service){this.service=service;}
    @GetMapping("/capabilities") @PreAuthorize("@ss.hasPermi('warehouse:carriers:list')")
    public AjaxResult capabilities(){return AjaxResult.success(service.capabilities());}
    @GetMapping("/connections") @PreAuthorize("@ss.hasPermi('warehouse:carriers:list')")
    public AjaxResult connections(){return AjaxResult.success(service.connections());}
    @GetMapping("/connections/{id}") @PreAuthorize("@ss.hasPermi('warehouse:carriers:query')")
    public AjaxResult connection(@PathVariable String id){return AjaxResult.success(service.connection(id));}
    @PostMapping("/connections") @PreAuthorize("@ss.hasPermi('warehouse:carriers:configure')")
    public AjaxResult create(@RequestBody Map<String,Object> input){return AjaxResult.success(service.save(null,input));}
    @PutMapping("/connections/{id}") @PreAuthorize("@ss.hasPermi('warehouse:carriers:configure')")
    public AjaxResult save(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.save(id,input));}
    @GetMapping("/shipments") @PreAuthorize("@ss.hasPermi('warehouse:carriers:list')")
    public AjaxResult shipments(@RequestParam(defaultValue="1")int pageNum,@RequestParam(defaultValue="20")int pageSize){return AjaxResult.success(service.shipments(pageNum,pageSize));}
    @GetMapping("/shipments/{id}") @PreAuthorize("@ss.hasPermi('warehouse:carriers:query')")
    public AjaxResult shipment(@PathVariable String id){return AjaxResult.success(service.shipment(id));}
    @GetMapping("/orders/{order}") @PreAuthorize("@ss.hasPermi('warehouse:carriers:query')")
    public AjaxResult order(@PathVariable String order){return AjaxResult.success(service.orderShipments(order));}
    @PostMapping("/orders/{order}/services") @PreAuthorize("@ss.hasPermi('warehouse:carriers:services')")
    public AjaxResult services(@PathVariable String order,@RequestBody Map<String,Object> input){return AjaxResult.success(service.services(order,input));}
    @PostMapping("/orders/{order}/book") @PreAuthorize("@ss.hasPermi('warehouse:carriers:book')")
    public AjaxResult book(@PathVariable String order,@RequestBody Map<String,Object> input){return AjaxResult.success(service.book(order,input));}
    @PostMapping("/shipments/{id}/recover") @PreAuthorize("@ss.hasPermi('warehouse:carriers:recover')")
    public AjaxResult recover(@PathVariable String id){return AjaxResult.success(service.recover(id));}
    @PostMapping("/orders/{id}/resolve") @PreAuthorize("@ss.hasPermi('warehouse:carriers:book')")
    public AjaxResult resolveBook(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.resolve("book",id,input));}
    @PostMapping("/connections/{id}/resolve") @PreAuthorize("@ss.hasPermi('warehouse:carriers:configure')")
    public AjaxResult resolveConfig(@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.resolve("config",id,input));}
    @GetMapping("/shipments/{id}/labels/{index}") @PreAuthorize("@ss.hasPermi('warehouse:carriers:label')")
    public ResponseEntity<byte[]> label(@PathVariable String id,@PathVariable int index){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"carrier-label-"+index+".zpl\"").header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType("text/vnd.zebra-zpl")).body(service.label(id,index));}
}
