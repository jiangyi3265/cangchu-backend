package com.ruoyi.warehouse.integrations;

/** Only fixed error codes cross the provider boundary; remote bodies and secrets never do. */
public final class IntegrationFailure extends RuntimeException {
    private final String code;
    public IntegrationFailure(String code) { super(code); this.code=code; }
    public String code() { return code; }
}
