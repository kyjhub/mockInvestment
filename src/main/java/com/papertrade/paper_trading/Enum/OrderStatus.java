package com.papertrade.paper_trading.Enum;

public enum OrderStatus {
    PENDING,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED,

    /**
     * 당일 유효 주문이 거래일 종료까지 체결되지 않아 실효된 상태.
     *
     * <p>사용자가 직접 취소한 {@link #CANCELED}와 구분한다. 주문 조회 화면에서 "내가 취소함"과
     * "장 마감으로 실효됨"이 같아 보이면 문의가 생긴다.
     */
    EXPIRED
}
