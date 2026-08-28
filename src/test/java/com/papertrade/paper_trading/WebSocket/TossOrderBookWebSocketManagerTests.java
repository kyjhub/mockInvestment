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
    void assignmentDependsOnlyOnTheSharedOrderedList() {
        // 거절 정보는 슬롯 소유자만 안다. 그것으로 목록을 먼저 걸러 배정하면 서버마다 결과가 달라져
        // 어떤 종목은 두 연결이 중복 구독하고 어떤 종목은 아무도 구독하지 않게 된다.
        // 배정은 원본 목록만 보고 계산되어야 어느 인스턴스에서 돌려도 같은 결과가 나온다.
        List<String> assigned = List.of("A", "B", "C", "D");

        assertThat(manager.symbolsForSlot(assigned, 0)).containsExactly("A", "C");
        assertThat(manager.symbolsForSlot(assigned, 1)).containsExactly("B", "D");

        // 거절된 A를 뺀 목록으로 배정하면 B와 D가 겹치고 C는 누락된다 — 그래서 이 방식을 쓰지 않는다.
        List<String> filtered = List.of("B", "C", "D");
        assertThat(manager.symbolsForSlot(filtered, 0)).containsExactly("B", "D");
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
