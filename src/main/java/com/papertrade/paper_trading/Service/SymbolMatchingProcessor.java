package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 종목 락 획득 → 매칭 → 락 해제까지의 공통 실행 경로.
 *
 * <p>스트림 컨슈머와 dirty drainer 두 곳에서 호출한다. 이 클래스가 없으면 호출자가
 * {@code MatchingEngineTransactionService.matchSymbol()}을 직접 부르게 되는데,
 * 그 메서드에는 종목 락이 없어 다중 인스턴스에서 중복 체결이 발생한다.
 *
 * <p>결과는 값으로 돌려주고 예상하지 못한 예외는 전파한다. 호출자마다 대응이 다르기 때문이다 —
 * 컨슈머는 재시도·DLQ로 보내고, drainer는 로그만 남긴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SymbolMatchingProcessor {

    private final SymbolOrderLockService symbolOrderLockService;
    private final DailyPriceRangeService dailyPriceRangeService;
    private final MatchingEngineTransactionService matchingEngineTransactionService;

    public SymbolMatchingResult process(String symbol) {
        String lockValue;
        try {
            lockValue = symbolOrderLockService.acquire(symbol);
        } catch (IllegalArgumentException e) {
            return SymbolMatchingResult.LOCK_BUSY;
        }

        try {
            DailyPriceRangeResponse dailyPriceRange = dailyPriceRangeService.getDailyPriceRange(symbol);
            // 호가가 매칭 도중 바뀌면 그 변경이 스스로 다음 트리거를 만든다. 여기서 반복하지 않는다.
            matchingEngineTransactionService.matchSymbol(symbol, dailyPriceRange);
            return SymbolMatchingResult.SUCCESS;
        } catch (TossApiQuotaUnavailableException e) {
            log.debug("Skip matching because Toss API quota is unavailable. symbol={}", symbol);
            return SymbolMatchingResult.QUOTA_UNAVAILABLE;
        } finally {
            symbolOrderLockService.release(symbol, lockValue);
        }
    }
}
