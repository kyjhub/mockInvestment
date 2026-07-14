package com.papertrade.paper_trading.Dto;

import java.time.LocalDate;

public record MarketBusinessDay(
    LocalDate date,
    MarketSession dayMarket,
    MarketSession preMarket,
    MarketSession regularMarket,
    MarketSession afterMarket
) {
}
