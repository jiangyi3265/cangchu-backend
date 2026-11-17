package com.ruoyi.warehouse.controller;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.warehouse.data.*;
import com.ruoyi.warehouse.support.WarehouseException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/warehouse/master/{kind}")
public class MasterTransferController {
    private final MasterImportService service;public MasterTransferController(MasterImportService service){this.service=service;}
    @PostMapping("/imports/preview") public AjaxResult preview(@PathVariable String kind,@RequestParam String mode,@RequestParam MultipartFile file)throws java.io.IOException{if(file.getSize()>MasterWorkbook.MAX_BYTES)throw WarehouseException.invalid("File exceeds 2 MiB / 文件超过 2 MiB");return AjaxResult.success(service.preview(kind,mode,file.getOriginalFilename(),file.getBytes()));}
    @GetMapping("/imports") public AjaxResult list(@PathVariable String kind){return AjaxResult.success(service.list(kind));}
    @GetMapping("/imports/{id}") public AjaxResult get(@PathVariable String kind,@PathVariable String id){return AjaxResult.success(service.get(kind,id));}
    @PostMapping("/imports/{id}/actions") public AjaxResult action(@PathVariable String kind,@PathVariable String id,@RequestBody Map<String,Object> input){return AjaxResult.success(service.action(kind,id,input));}
    @GetMapping("/workbook") public ResponseEntity<byte[]> workbook(@PathVariable String kind,@RequestParam(defaultValue="false")boolean template,@RequestParam(defaultValue="create")String mode,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="500")int size,@RequestParam(defaultValue="")String keyword){byte[] bytes=service.workbook(kind,template,mode,page,size,keyword);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+kind+(template?"-template":"-export-"+page)+".xlsx\"").body(bytes);}
}
