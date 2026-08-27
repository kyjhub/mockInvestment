package com.papertrade.paper_trading.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * dirty set은 "꺼낸 뒤 매칭" 순서라 신호가 유실될 수 있는 지점이 있다.
 * 락 경합 시 재등록, 종목별 예외 격리, 예산 부족 시 hot loop 방지가 그 경계다.
 */
class DirtyOrderBookSymbolDrainSchedulerTests {

    private final DirtyOrderBookSymbolRegistry dirtyRegistry = mock(DirtyOrderBookSymbolRegistry.class);
    private final ActiveOrderBookSymbolRegistry activeRegistry = mock(ActiveOrderBookSymbolRegistry.class);
    private final SymbolMatchingProcessor processor = mock(SymbolMatchingProcessor.class);

    private DirtyOrderBookSymbolDrainScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DirtyOrderBookSymbolDrainScheduler(dirtyRegistry, activeRegistry, processor);
        setBatchSize(scheduler, 20L);
        when(activeRegistry.pendingOrderSymbols()).thenReturn(List.of("AAPL", "TSLA", "NVDA"));
    }

    @Test
    void reRegistersSymbolWhenLockIsBusySoTheSignalIsNotLost() {
        // 다른 처리자가 락을 쥐고 있는 동안 꺼낸 종목을 그냥 버리면
        // 마지막 호가 변경 신호가 사라져 30초 안전망까지 기다리게 된다.
        when(dirtyRegistry.pop(anyLong())).thenReturn(List.of("AAPL"));
        when(processor.process("AAPL")).thenReturn(SymbolMatchingResult.LOCK_BUSY);

        scheduler.drain();

        verify(dirtyRegistry).markDirty("AAPL");
    }

    @Test
    void doesNotReRegisterOnQuotaExhaustionToAvoidHotLoop() {
        // 예산이 없는 동안 재등록하면 드레인 주기마다 도는 hot loop가 된다.
        when(dirtyRegistry.pop(anyLong())).thenReturn(List.of("AAPL"));
        when(processor.process("AAPL")).thenReturn(SymbolMatchingResult.QUOTA_UNAVAILABLE);

        scheduler.drain();

        verify(dirtyRegistry, never()).markDirty(anyString());
    }

    @Test
    void oneFailingSymbolDoesNotDiscardTheRestOfTheBatch() {
        // 이미 SPOP으로 꺼낸 종목들은 집합에 없다. 여기서 루프가 중단되면 나머지가 통째로 유실된다.
        when(dirtyRegistry.pop(anyLong())).thenReturn(List.of("AAPL", "TSLA", "NVDA"));
        List<String> processed = new ArrayList<>();
        when(processor.process(anyString())).thenAnswer(invocation -> {
            String symbol = invocation.getArgument(0);
            processed.add(symbol);
            if ("TSLA".equals(symbol)) {
                throw new IllegalStateException("DB connection lost");
            }
            return SymbolMatchingResult.SUCCESS;
        });

        scheduler.drain();

        assertThat(processed).containsExactly("AAPL", "TSLA", "NVDA");
    }

    @Test
    void skipsSymbolsWithoutPendingOrders() {
        // 화면으로 보고만 있는 종목은 매칭할 대상이 없다.
        when(dirtyRegistry.pop(anyLong())).thenReturn(List.of("AAPL", "MSFT"));
        when(processor.process(anyString())).thenReturn(SymbolMatchingResult.SUCCESS);

        scheduler.drain();

        verify(processor).process("AAPL");
        verify(processor, never()).process("MSFT");
    }

    private void setBatchSize(DirtyOrderBookSymbolDrainScheduler target, long value) {
        try {
            Field field = DirtyOrderBookSymbolDrainScheduler.class.getDeclaredField("batchSize");
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
