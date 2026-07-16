package com.papertrade.paper_trading.Dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CandleResult(
    List<Candle> candles,
    OffsetDateTime nextBefore
) {
}
