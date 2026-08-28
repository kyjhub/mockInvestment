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
    void neverDuplicatesEvenWhenInstancesSeeDifferentSymbolLists() {
        // 구독·주문 추가 시점 차이나 Redis 장애로 인스턴스마다 목록이 갈릴 수 있다.
        // 위치(인덱스)로 슬롯을 나누면 같은 위치가 서로 다른 종목을 가리켜 중복과 누락이 함께 생긴다.
        // 소유권이 종목 이름으로 정해지므로 목록이 달라도 중복은 구조적으로 불가능해야 한다.
        List<String> serverOneView = List.of("B", "C", "D");
        List<String> serverTwoView = List.of("A", "B", "C", "D");

        List<String> slot0 = manager.symbolsForSlot(serverOneView, 0, Set.of());
        List<String> slot1 = manager.symbolsForSlot(serverTwoView, 1, Set.of());

        assertThat(slot0).doesNotContainAnyElementsOf(slot1);
    }

    @Test
    void assignsEachSymbolToTheSameSlotRegardlessOfListContents() {
        // 목록이 어떻게 바뀌든 한 종목의 소유 슬롯은 변하지 않아야 한다.
        for (String symbol : symbols(50)) {
            int fromFullList = slotOf(symbol, symbols(50));
            int fromPartialList = slotOf(symbol, List.of(symbol));
            assertThat(fromFullList).isEqualTo(fromPartialList);
        }
    }

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
    void skipsRejectedSymbolsWithoutAffectingOtherSlots() {
        List<String> ordered = symbols(20);
        String rejectedSymbol = ordered.get(0);
        int owner = slotOf(rejectedSymbol, ordered);

        assertThat(manager.symbolsForSlot(ordered, owner, Set.of())).contains(rejectedSymbol);
        assertThat(manager.symbolsForSlot(ordered, owner, Set.of(rejectedSymbol))).doesNotContain(rejectedSymbol);

        // 거절 정보는 슬롯 소유자만 안다. 다른 슬롯의 배정이 그 때문에 달라지면 안 된다.
        int otherSlot = (owner + 1) % SLOTS;
        assertThat(manager.symbolsForSlot(ordered, otherSlot, Set.of(rejectedSymbol)))
            .isEqualTo(manager.symbolsForSlot(ordered, otherSlot, Set.of()));
    }

    @Test
    void divergentRejectionKnowledgeStillProducesNoOverlap() {
        List<String> ordered = symbols(20);
        String rejectedSymbol = ordered.get(0);

        // 서버1은 거절을 알고, 서버2는 모른다.
        List<String> slot0 = manager.symbolsForSlot(ordered, 0, Set.of(rejectedSymbol));
        List<String> slot1 = manager.symbolsForSlot(ordered, 1, Set.of());

        assertThat(slot0).doesNotContainAnyElementsOf(slot1);
    }

    private int slotOf(String symbol, List<String> ordered) {
        for (int slotIndex = 0; slotIndex < SLOTS; slotIndex++) {
            if (manager.symbolsForSlot(ordered, slotIndex, Set.of()).contains(symbol)) {
                return slotIndex;
            }
        }
        throw new IllegalStateException("어떤 슬롯에도 배정되지 않았다: " + symbol);
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
