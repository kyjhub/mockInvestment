package com.papertrade.paper_trading.Dto;

// 장 운영정보 조회 DTO
public record MarketCalendarResult(
    MarketBusinessDay today,
    MarketBusinessDay previousBusinessDay,
    MarketBusinessDay nextBusinessDay
) {
}
