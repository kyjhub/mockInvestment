package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Config.SchedulingConfig;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 호가가 갱신된 종목을 꺼내 매칭한다.
 *
 * <p>처리량 상한이 "미체결 주문 종목 수 × 드레인 주기"로 정해지므로, 푸시 빈도가 아무리 늘어도
 * 매칭 부하가 따라 늘지 않는다. 스트림에는 이 성질이 없어 유입이 소비를 넘기면 영구히 밀렸다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirtyOrderBookSymbolDrainScheduler {

    private final DirtyOrderBookSymbolRegistry dirtySymbolRegistry;
    private final ActiveOrderBookSymbolRegistry activeSymbolRegistry;
    private final SymbolMatchingProcessor symbolMatchingProcessor;

    /** 한 번에 꺼내는 종목 수. 크게 잡으면 한 사이클이 길어져 fixedDelay 간격 가정이 깨진다. */
    @Value("${matching-engine.dirty-drain.batch-size:20}")
    private long batchSize;

    @Scheduled(
        scheduler = SchedulingConfig.DIRTY_DRAIN_SCHEDULER,
        fixedDelayString = "${matching-engine.dirty-drain.fixed-delay-ms:150}"
    )
    public void drain() {
        List<String> symbols = dirtySymbolRegistry.pop(batchSize);
        if (symbols.isEmpty()) {
            return;
        }

        Set<String> pendingSymbols = Set.copyOf(activeSymbolRegistry.pendingOrderSymbols());
        for (String symbol : symbols) {
            // 한 종목의 실패가 이미 꺼낸 나머지 종목을 유실시키지 않도록 종목별로 격리한다.
            try {
                drainSymbol(symbol, pendingSymbols);
            } catch (Exception e) {
                // 결정적 실패는 재등록해도 반복된다. 30초 안전망이 스트림으로 재발행해 복구를 보장한다.
                log.error("Dirty drain failed. symbol={}", symbol, e);
            }
        }
    }

    private void drainSymbol(String symbol, Set<String> pendingSymbols) {
        if (!pendingSymbols.contains(symbol)) {
            return;  // 미체결 주문이 없으면 매칭할 대상이 없다
        }

        SymbolMatchingResult result = symbolMatchingProcessor.process(symbol);
        if (result == SymbolMatchingResult.LOCK_BUSY) {
            // 락 소유자가 곧 끝나므로 자기 제한적이다. 재등록하지 않으면 마지막 호가 변경 신호가 사라져
            // 거래가 뜸한 종목은 30초 안전망까지 기다리게 된다.
            dirtySymbolRegistry.markDirty(symbol);
        }
        // QUOTA_UNAVAILABLE은 재등록하지 않는다. 예산이 없는 동안 재등록하면 드레인 주기마다 도는
        // hot loop가 된다. 예산이 풀리면 다음 푸시나 폴링이 신호를 다시 만든다.
    }
}
