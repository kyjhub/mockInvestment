package com.papertrade.paper_trading.Dto;

/** 반대분개 결과. 원거래는 그대로 남고, 상쇄하는 거래가 새로 추가된다. */
public record LedgerReversalResult(
    Long originalTransactionId,
    Long reversalTransactionId,
    int entryCount
) {
}
