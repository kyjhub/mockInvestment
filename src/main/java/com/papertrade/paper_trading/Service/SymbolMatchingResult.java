package com.papertrade.paper_trading.Service;

/**
 * 종목 매칭 시도의 결과. 호출자(스트림 컨슈머 / dirty drainer)마다 후속 처리가 달라서
 * 예외 대신 값으로 돌려준다. 예상하지 못한 오류는 값이 아니라 예외로 전파된다.
 */
public enum SymbolMatchingResult {

    /** 매칭을 수행했다. 체결이 0건이어도 정상이다. */
    SUCCESS,

    /** 다른 처리자가 같은 종목의 락을 쥐고 있어 이번에는 건너뛰었다. */
    LOCK_BUSY,

    /** 토스 API 예산이 없어 호가·일봉을 가져오지 못했다. 실패가 아니라 대기 상태다. */
    QUOTA_UNAVAILABLE
}
