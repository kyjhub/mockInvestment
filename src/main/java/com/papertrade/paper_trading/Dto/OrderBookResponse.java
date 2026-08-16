package com.papertrade.paper_trading.Dto;

import java.time.LocalDateTime;

public record OrderBookResponse(
    OrderBookResult result,
    LocalDateTime receivedAt
) {
}
