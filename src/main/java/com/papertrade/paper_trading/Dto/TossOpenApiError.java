package com.papertrade.paper_trading.Dto;

public record TossOpenApiError(
    String requestId,
    String code,
    String message
) {
}
