package com.papertrade.paper_trading.Enum;

/**
 * 분개의 계정과목.
 *
 * <p>부호 규약은 표준 회계를 따른다. 자산 계정({@link #CASH}, {@link #SECURITIES})은 증가가 {@code +},
 * 수익·자본 계정({@link #REALIZED_PNL}, {@link #EQUITY_FUNDING})은 증가가 {@code −},
 * 비용 계정({@link #FEE}, {@link #TAX})은 발생이 {@code +}다.
 *
 * <p>한 거래의 분개 합계는 항상 0이다. 이 불변식이 "버그로 돈이 생기거나 사라졌다"를 탐지하는 수단이다.
 */
public enum LedgerAccount {

    /** 예수금. */
    CASH,

    /** 보유 유가증권의 취득원가. 평가액이 아니다. */
    SECURITIES,

    /** 실현손익. 이익이 대변({@code −}). */
    REALIZED_PNL,

    /** 수수료 비용. */
    FEE,

    /** 거래세 비용. */
    TAX,

    /** 모의 투자금 지급. 개시 입금과 충전의 상대 계정. */
    EQUITY_FUNDING
}
