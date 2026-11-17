package com.ruoyi.warehouse.carriers;

/** Fixed public codes; never put provider response bodies or credentials in an exception. */
public class CarrierFailure extends RuntimeException {
    private final String code;
    private final boolean rejected;
    public CarrierFailure(String code){this(code,false);}
    public CarrierFailure(String code,boolean rejected){super(code);this.code=code;this.rejected=rejected;}
    public String code(){return code;}
    public boolean definitelyRejected(){return rejected;}
}
