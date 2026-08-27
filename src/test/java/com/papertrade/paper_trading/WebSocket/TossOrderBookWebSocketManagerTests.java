package com.papertrade.paper_trading.WebSocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TossOrderBookWebSocketManagerTests {

    private static final int SLOTS = 2;
    private static final int MAX_PER_CONNECTION = 100;

    private final TossOrderBookWebSocketManager manager = new TossOrderBookWebSocketManager(
        properties(SLOTS, MAX_PER_CONNECTION),
        null,
        null,
        null,
        null,
        null
    );

    @Test
    void splitsSymbolsAcrossSlotsWithoutOverlapOrLoss() {
        List<String> assigned = symbols(150);

        List<String> slot0 = manager.symbolsForSlot(assigned, 0);
        List<String> slot1 = manager.symbolsForSlot(assigned, 1);

        assertThat(slot0).doesNotContainAnyElementsOf(slot1);

        List<String> union = new ArrayList<>(slot0);
        union.addAll(slot1);
        assertThat(union).containsExactlyInAnyOrderElementsOf(assigned);
    }

    @Test
    void neverExceedsPerConnectionSubscriptionLimit() {
        // 슬롯 전체 수용량(200)을 넘는 입력이 들어와도 연결당 한도를 넘지 않아야 한다.
        // 넘으면 토스가 선언 전체를 too-many-topics로 거부한다.
        List<String> assigned = symbols(260);

        for (int slotIndex = 0; slotIndex < SLOTS; slotIndex++) {
            assertThat(manager.symbolsForSlot(assigned, slotIndex)).hasSizeLessThanOrEqualTo(MAX_PER_CONNECTION);
        }
    }

    @Test
    void handlesFewerSymbolsThanSlots() {
        List<String> assigned = List.of("AAPL");

        assertThat(manager.symbolsForSlot(assigned, 0)).containsExactly("AAPL");
        assertThat(manager.symbolsForSlot(assigned, 1)).isEmpty();
    }

    private List<String> symbols(int count) {
        List<String> symbols = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            symbols.add("SYM" + i);
        }
        return symbols;
    }

    private TossWebSocketProperties properties(int connectionSlots, int maxSymbolsPerConnection) {
        TossWebSocketProperties properties = new TossWebSocketProperties();
        setField(properties, "connectionSlots", connectionSlots);
        setField(properties, "maxSymbolsPerConnection", maxSymbolsPerConnection);
        return properties;
    }

    private void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
