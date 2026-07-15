package com.papertrade.paper_trading.Dto;

import java.util.List;

public record PriceResponse(
    List<PriceResult> result
) {
}
