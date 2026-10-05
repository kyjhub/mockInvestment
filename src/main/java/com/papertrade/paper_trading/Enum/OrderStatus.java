package com.papertrade.paper_trading.Enum;

public enum OrderStatus {

    /**
     * 접수는 했지만 현재가를 확보하지 못해 접수 검증을 마치지 못한 주문. 매칭에 쓰지 않는다.
     *
     * <p>지정가는 가격 밴드 검증이, 시장가는 주문가격 변환(매수는 주문가능금액 검증까지)이 남아 있다.
     * 현재가를 확보하면 검증을 마치고 {@link #PENDING}이 되거나 거절된다.
     */
    AWAITING_PRICE,

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
