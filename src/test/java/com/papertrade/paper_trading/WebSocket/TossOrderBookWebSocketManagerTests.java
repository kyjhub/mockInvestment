package com.papertrade.paper_trading.WebSocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        List<String> ordered = symbols(150);

        List<String> slot0 = manager.symbolsForSlot(ordered, 0, Set.of());
        List<String> slot1 = manager.symbolsForSlot(ordered, 1, Set.of());

        assertThat(slot0).doesNotContainAnyElementsOf(slot1);

        List<String> union = new ArrayList<>(slot0);
        union.addAll(slot1);
        assertThat(union).containsExactlyInAnyOrderElementsOf(ordered);
    }

    @Test
    void neverExceedsPerConnectionSubscriptionLimit() {
        // 슬롯 전체 수용량(200)을 넘는 입력이 들어와도 연결당 한도를 넘지 않아야 한다.
        // 넘으면 토스가 선언 전체를 too-many-topics로 거부한다.
        List<String> ordered = symbols(260);

        for (int slotIndex = 0; slotIndex < SLOTS; slotIndex++) {
            assertThat(manager.symbolsForSlot(ordered, slotIndex, Set.of()))
                .hasSizeLessThanOrEqualTo(MAX_PER_CONNECTION);
        }
    }

    @Test
    void rejectedSymbolDoesNotLeaveAnEmptyAssignmentSlot() {
        // 거절된 종목이 자리만 차지하면 슬롯이 한도보다 적게 채워지고 구독 정원이 낭비된다.
        // 자기 계열에서 다음 종목을 당겨와 채워야 한다.
        List<String> ordered = List.of("A", "B", "C", "D", "E", "F");

        assertThat(manager.symbolsForSlot(ordered, 0, Set.of())).containsExactly("A", "C", "E");
        assertThat(manager.symbolsForSlot(ordered, 0, Set.of("A"))).containsExactly("C", "E");
    }

    @Test
    void divergentRejectionKnowledgeStillProducesNoOverlapAndNoGap() {
        // 거절 정보는 슬롯을 소유한 인스턴스만 안다. 서버마다 아는 내용이 달라도
        // 각 슬롯은 자기 인덱스 계열만 훑으므로 중복도 누락도 생기지 않아야 한다.
        List<String> ordered = List.of("A", "B", "C", "D", "E", "F");

        // 서버1은 slot 0을 갖고 A가 거절된 것을 안다.
        List<String> slot0 = manager.symbolsForSlot(ordered, 0, Set.of("A"));
        // 서버2는 slot 1을 갖고 A 거절을 모른다.
        List<String> slot1 = manager.symbolsForSlot(ordered, 1, Set.of());

        assertThat(slot0).doesNotContainAnyElementsOf(slot1);

        List<String> union = new ArrayList<>(slot0);
        union.addAll(slot1);
        // 거절된 A만 빠지고 나머지는 정확히 한 번씩 구독된다.
        assertThat(union).containsExactlyInAnyOrder("B", "C", "D", "E", "F");
    }

    @Test
    void handlesFewerSymbolsThanSlots() {
        List<String> ordered = List.of("AAPL");

        assertThat(manager.symbolsForSlot(ordered, 0, Set.of())).containsExactly("AAPL");
        assertThat(manager.symbolsForSlot(ordered, 1, Set.of())).isEmpty();
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
