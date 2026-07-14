package com.papertrade.paper_trading.Enum;

/** 초기 모의투자금 지급 **
 * 주식 매수로 현금 감소
 * 주식 매도로 현금 증가
 * 수수료 차감
 * 세금 차감
 * 운영자 보정
 */
public enum CashTransactionType {
    INITIAL_DEPOSIT,
    BUY,
    SELL,
    COMMISSION,
    TAX,
    ADJUSTMENT
}
