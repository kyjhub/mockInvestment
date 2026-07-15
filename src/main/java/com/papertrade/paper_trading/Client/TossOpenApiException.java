package com.papertrade.paper_trading.Client;

public class TossOpenApiException extends RuntimeException {

    private final int statusCode;
    private final String requestId;
    private final String code;

    public TossOpenApiException(int statusCode, String requestId, String code, String message) {
        super(message);
        this.statusCode = statusCode;
        this.requestId = requestId;
        this.code = code;
    }

    public int statusCode() {
        return statusCode;
    }

    public String requestId() {
        return requestId;
    }

    public String code() {
        return code;
    }
}
