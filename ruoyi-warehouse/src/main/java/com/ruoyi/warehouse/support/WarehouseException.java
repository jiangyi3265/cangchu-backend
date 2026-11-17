package com.ruoyi.warehouse.support;

public class WarehouseException extends RuntimeException {
    private final int status;
    private final String errorCode;
    public WarehouseException(int status, String errorCode, String message) {
        super(message); this.status=status; this.errorCode=errorCode;
    }
    public int getStatus(){return status;}
    public String getErrorCode(){return errorCode;}
    public static WarehouseException invalid(String message){return new WarehouseException(400,"warehouse_invalid_input",message);}
    public static WarehouseException conflict(String message){return new WarehouseException(409,"warehouse_conflict",message);}
    public static WarehouseException missing(){return new WarehouseException(404,"warehouse_not_found","Record not found / 记录不存在");}
    public static WarehouseException forbidden(){return new WarehouseException(403,"warehouse_scope_denied","No access to this client or warehouse / 无权访问此客户或仓库");}
}
