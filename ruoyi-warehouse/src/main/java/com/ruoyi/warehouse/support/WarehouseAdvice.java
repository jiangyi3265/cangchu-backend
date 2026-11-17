package com.ruoyi.warehouse.support;

import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages="com.ruoyi.warehouse")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WarehouseAdvice {
    @ExceptionHandler(com.ruoyi.warehouse.carriers.CarrierFailure.class)
    public ResponseEntity<AjaxResult> carrier(com.ruoyi.warehouse.carriers.CarrierFailure e){
        int status="CARRIER_ACCESS_REVOKED".equals(e.code())?403:409;
        AjaxResult body=AjaxResult.error(status,"Carrier operation needs attention / 快递操作需要核对");body.put("error",e.code());return ResponseEntity.status(status).body(body);
    }
    @ExceptionHandler(com.ruoyi.warehouse.integrations.IntegrationFailure.class)
    public ResponseEntity<AjaxResult> integration(com.ruoyi.warehouse.integrations.IntegrationFailure e){return business(new WarehouseException(409,e.code(),"Store verification did not pass / 平台核验未通过："+e.code()));}
    @ExceptionHandler(WarehouseException.class)
    public ResponseEntity<AjaxResult> business(WarehouseException e){AjaxResult body=AjaxResult.error(e.getStatus(),e.getMessage());body.put("error",e.getErrorCode());if(java.util.Set.of(400,403,404,409).contains(e.getStatus()))body.put("data",java.util.Map.of("committed",false));return ResponseEntity.status(e.getStatus()).body(body);}
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<AjaxResult> constraint(DataIntegrityViolationException e){return business(WarehouseException.conflict("Duplicate identity or referenced record / 编号重复或记录已被引用"));}
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<AjaxResult> concurrent(ConcurrencyFailureException e){return business(new WarehouseException(409,"warehouse_retry_transaction","A concurrent change was rolled back; retry the same request / 并发修改已回滚，请重试原请求"));}
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<AjaxResult> storage(org.springframework.dao.DataAccessException e){
        // A connection/commit failure has an uncertain outcome. Do not expose SQL or claim rollback.
        org.slf4j.LoggerFactory.getLogger(WarehouseAdvice.class).error("Warehouse storage failure: {}",e.getClass().getSimpleName());
        AjaxResult body=AjaxResult.error(500,"Storage request could not be confirmed; recover the original request / 数据提交结果未能确认，请核对原请求");body.put("error","warehouse_storage_unavailable");return ResponseEntity.status(500).body(body);
    }
}
