package com.papertrade.paper_trading.Dto;

import java.time.OffsetDateTime;

public record MarketSession(
    OffsetDateTime startTime,
    OffsetDateTime endTime
) {
}
