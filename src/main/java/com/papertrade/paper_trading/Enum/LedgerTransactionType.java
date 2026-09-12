package com.papertrade.paper_trading.Enum;

/** 원장 거래의 종류. 하나의 경제적 사건을 가리킨다. */
public enum LedgerTransactionType {

    /** 계좌 개설과 개시 투자금 지급. */
    ACCOUNT_OPENING,

    /** 투자금 추가 충전. */
    FUNDING,

    /** 체결. 수수료·세금 분개도 같은 거래에 묶인다. */
    TRADE,

    /** 체결과 분리된 독립 비용. */
    FEE,

    /** 수기 조정. */
    ADJUSTMENT,

    /** 계좌 초기화. */
    RESET
}
