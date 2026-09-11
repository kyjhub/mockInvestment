package com.papertrade.paper_trading.WebSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import com.papertrade.paper_trading.Service.ActiveOrderBookSymbolRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 인스턴스가 하나라 배정 권한자도 하나다. 중복은 구조적으로 불가능하고 정원에 못 든 종목은 폴링이 가져간다.
 * 그래서 여기서 지킬 것은 세 가지다 — 미체결 우선, 슬롯 유지, 연결당 한도.
 */
class TossOrderBookWebSocketManagerTests {

    private static final int SLOTS = 2;
    private static final int MAX_PER_CONNECTION = 3;

    private final ActiveOrderBookSymbolRegistry activeSymbolRegistry = mock(ActiveOrderBookSymbolRegistry.class);
    private final RejectedWebSocketSymbolRegistry rejectedSymbolRegistry =
        mock(RejectedWebSocketSymbolRegistry.class);
    private final DeclaredWebSocketSymbolRegistry declaredSymbolRegistry = new DeclaredWebSocketSymbolRegistry();

    private final TossOrderBookWebSocketManager manager = new TossOrderBookWebSocketManager(
        properties(),
        null,
        null,
        activeSymbolRegistry,
        null,
        rejectedSymbolRegistry,
        declaredSymbolRegistry,
        null
    );

    @Test
    void clearsDeclaredRegistryWhenThereIsNoLiveConnection() {
        // 매칭 게이트가 이 목록을 보고 REST를 건너뛴다. 슬롯을 전부 잃거나 WebSocket을 내렸는데도
        // 목록이 남으면, 캐시가 TTL로 사라진 뒤 외부 체결이 조용히 멈춘다.
        declaredSymbolRegistry.replaceAll(Set.of("AAPL"));

        manager.refreshSubscriptions();

        assertThat(declaredSymbolRegistry.declaredSymbols()).isEmpty();
    }

    @Test
    void fillsBothSlotsUpToCapacity() {
        activeSymbols("A", "B", "C", "D", "E", "F");

        Map<Integer, List<String>> plan = plan();

        assertThat(plan.get(0)).hasSize(MAX_PER_CONNECTION);
        assertThat(plan.get(1)).hasSize(MAX_PER_CONNECTION);
        assertThat(allOf(plan)).containsExactlyInAnyOrder("A", "B", "C", "D", "E", "F");
    }

    @Test
    void dropsLowestPrioritySymbolsWhenOverCapacity() {
        // 정원은 6인데 8종목이다. 뒤쪽(화면 구독 종목)이 밀려나 폴링으로 간다.
        activeSymbols("A", "B", "C", "D", "E", "F", "G", "H");

        assertThat(allOf(plan())).containsExactlyInAnyOrder("A", "B", "C", "D", "E", "F");
    }

    @Test
    void pendingOrderSymbolEvictsScreenSymbolWhenCapacityIsFull() {
        // orderedActiveSymbols()가 미체결 종목을 앞에 놓으므로, 새 미체결 종목이 들어오면
        // 뒤쪽 화면 종목이 밀려나야 한다. 체결은 신선한 호가가 필요하고 화면은 지연돼도 된다.
        activeSymbols("A", "B", "C", "D", "E", "F");
        plan();

        // NEW가 미체결 종목으로 맨 앞에 붙고 F(화면 구독)가 밀려난다.
        activeSymbols("NEW", "A", "B", "C", "D", "E", "F");
        Set<String> covered = allOf(plan());

        assertThat(covered).contains("NEW");
        assertThat(covered).doesNotContain("F");
    }

    @Test
    void keepsAlreadyAssignedSymbolsInTheSameSlot() {
        // 슬롯을 옮기면 새 구독이 되는데 토스는 초기 스냅샷을 주지 않아 데이터가 끊긴다.
        activeSymbols("A", "B", "C", "D");
        Map<Integer, List<String>> before = plan();

        // 앞에 새 종목이 붙어 순서가 밀려도 기존 종목의 슬롯은 그대로여야 한다.
        activeSymbols("NEW", "A", "B", "C", "D");
        Map<Integer, List<String>> after = plan();

        for (String symbol : List.of("A", "B", "C", "D")) {
            assertThat(slotOf(after, symbol))
                .as("%s 의 슬롯", symbol)
                .isEqualTo(slotOf(before, symbol));
        }
    }

    @Test
    void releasesSlotWhenSymbolIsNoLongerActive() {
        // 체결이 끝나 미체결 목록에서 빠지면 자리를 내줘야 대기 종목이 들어온다.
        activeSymbols("A", "B", "C", "D", "E", "F");
        plan();

        activeSymbols("A", "B", "C", "D", "E", "NEW");
        Set<String> covered = allOf(plan());

        assertThat(covered).contains("NEW");
        assertThat(covered).doesNotContain("F");
    }

    @Test
    void excludesRejectedSymbolsSoTheyDoNotHoldCapacity() {
        activeSymbols("A", "B", "C", "D", "E", "F", "G");
        when(rejectedSymbolRegistry.rejectedSymbols()).thenReturn(Set.of("B"));

        Set<String> covered = allOf(plan());

        assertThat(covered).doesNotContain("B");
        // B가 자리만 차지하지 않고 다음 종목이 그 자리를 채운다.
        assertThat(covered).hasSize(SLOTS * MAX_PER_CONNECTION).contains("G");
    }

    private void activeSymbols(String... symbols) {
        when(activeSymbolRegistry.orderedActiveSymbols()).thenReturn(List.of(symbols));
    }

    private Integer slotOf(Map<Integer, List<String>> plan, String symbol) {
        return plan.entrySet().stream()
            .filter(entry -> entry.getValue().contains(symbol))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    private Set<String> allOf(Map<Integer, List<String>> plan) {
        Set<String> all = new java.util.LinkedHashSet<>();
        plan.values().forEach(all::addAll);
        return all;
    }

    /** 슬롯 두 개를 이 인스턴스가 소유한 상태를 만들고 배정을 계산한다. */
    @SuppressWarnings("unchecked")
    private Map<Integer, List<String>> plan() {
        try {
            Field connectionsField = TossOrderBookWebSocketManager.class.getDeclaredField("connections");
            connectionsField.setAccessible(true);
            Map<Integer, Object> connections = (Map<Integer, Object>) connectionsField.get(manager);
            for (int slotIndex = 0; slotIndex < SLOTS; slotIndex++) {
                connections.putIfAbsent(slotIndex, new Object());
            }

            Method method = TossOrderBookWebSocketManager.class.getDeclaredMethod("planSlotAssignments");
            method.setAccessible(true);
            return new HashMap<>((Map<Integer, List<String>>) method.invoke(manager));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private TossWebSocketProperties properties() {
        TossWebSocketProperties properties = new TossWebSocketProperties();
        setField(properties, "connectionSlots", SLOTS);
        setField(properties, "maxSymbolsPerConnection", MAX_PER_CONNECTION);
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
