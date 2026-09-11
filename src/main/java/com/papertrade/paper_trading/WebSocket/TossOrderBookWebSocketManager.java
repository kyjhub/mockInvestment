package com.papertrade.paper_trading.WebSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossAccessTokenProvider;
import com.papertrade.paper_trading.Config.SchedulingConfig;
import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import com.papertrade.paper_trading.Service.ActiveOrderBookSymbolRegistry;
import com.papertrade.paper_trading.Service.OrderBookService;
import jakarta.annotation.PreDestroy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 슬롯 점유와 종목 배정을 관리한다. 실제 연결/수신은 {@link TossOrderBookWebSocketConnection}이 담당한다.
 * 모든 상태 전이가 스케줄 틱에서 일어나므로 별도 스레드 관리가 없다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TossOrderBookWebSocketManager {

    private final TossWebSocketProperties properties;
    private final TossWebSocketSlotLock slotLock;
    private final TossAccessTokenProvider accessTokenProvider;
    private final ActiveOrderBookSymbolRegistry activeSymbolRegistry;
    private final OrderBookService orderBookService;
    private final RejectedWebSocketSymbolRegistry rejectedSymbolRegistry;
    private final DeclaredWebSocketSymbolRegistry declaredSymbolRegistry;
    private final ObjectMapper objectMapper;

    private final Map<Integer, TossOrderBookWebSocketConnection> connections = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /**
     * 종목이 어느 슬롯에 배정됐는지. 슬롯을 유지해 재구독으로 인한 데이터 공백을 막는다.
     * 배정 스케줄러(단일 스레드)에서만 수정한다.
     */
    private final Map<String, Integer> slotBySymbol = new HashMap<>();

    /** 직전 배정 결과. 폴링 스레드가 읽는다. */
    private volatile List<String> coveredSnapshot = List.of();

    /** 슬롯을 잡거나 유지한다. 한 인스턴스가 두 슬롯을 다 잡아도 된다 — 인스턴스가 하나뿐일 때 필요하다. */
    @Scheduled(
        scheduler = SchedulingConfig.WEBSOCKET_SCHEDULER,
        fixedDelayString = "${toss-invest.websocket.slot-heartbeat-ms:5000}"
    )
    public void manageSlots() {
        if (!properties.enabled()) {
            releaseAll();
            return;
        }

        for (int slotIndex = 0; slotIndex < properties.connectionSlots(); slotIndex++) {
            if (connections.containsKey(slotIndex)) {
                if (!slotLock.renew(slotIndex)) {
                    log.warn("Toss WebSocket slot lost. slot={}", slotIndex);
                    closeSlot(slotIndex);
                }
                continue;
            }

            if (slotLock.acquire(slotIndex)) {
                log.info("Toss WebSocket slot acquired. slot={}", slotIndex);
                connections.put(slotIndex, newConnection(slotIndex));
            }
        }
    }

    /** 담당 종목 재계산 + 변경 시 재선언. 이 주기가 곧 선언 디바운스다. */
    @Scheduled(
        scheduler = SchedulingConfig.WEBSOCKET_SCHEDULER,
        fixedDelayString = "${toss-invest.websocket.declare-debounce-ms:250}"
    )
    public void refreshSubscriptions() {
        if (!properties.enabled() || connections.isEmpty()) {
            coveredSnapshot = List.of();
            // 비우지 않으면 WebSocket을 내린 뒤에도 마지막 선언 목록이 남아, 매칭이 REST 폴백을 영영 건너뛴다.
            // 캐시는 TTL로 사라지므로 그 뒤로는 외부 체결이 조용히 멈춘다.
            declaredSymbolRegistry.replaceAll(Set.of());
            return;
        }

        Map<Integer, List<String>> plan = planSlotAssignments();
        Set<String> covered = new LinkedHashSet<>();
        for (Map.Entry<Integer, TossOrderBookWebSocketConnection> entry : connections.entrySet()) {
            List<String> slotSymbols = plan.getOrDefault(entry.getKey(), List.of());
            covered.addAll(slotSymbols);

            TossOrderBookWebSocketConnection connection = entry.getValue();
            connection.setDesiredSymbols(slotSymbols);
            connection.ensureConnected();
            connection.declareIfChanged();
            connection.pingIfDue();
        }
        coveredSnapshot = List.copyOf(covered);
        // 매칭 게이트는 covered가 아니라 이 목록을 본다. connection이 실패 경로마다 자기 선언을 비우므로
        // 연결이 죽으면 다음 틱에 여기서 빠지고, 매칭은 그 순간부터 REST 경로로 돌아간다.
        declaredSymbolRegistry.replaceAll(declaredSymbols());
    }

    /**
     * 슬롯별 구독 목록을 계산한다.
     *
     * <p>인스턴스가 하나라 배정 권한자도 하나다. 목록을 한 번만 계산하므로 중복 구독이 생길 수 없고,
     * 정원에 못 든 종목은 REST 폴링이 가져가므로 누락도 없다.
     *
     * <p>핵심은 <b>이미 배정된 종목의 슬롯을 유지</b>하는 것이다. 슬롯을 옮기면 그 종목은 새 구독이 되는데,
     * 토스는 구독 직후 초기 스냅샷을 주지 않아 다음 호가 변동까지 데이터가 끊긴다. 거래가 뜸한 종목이면
     * 그 공백이 길어지므로, 옮길 이유가 없으면 옮기지 않는다.
     *
     * <p>다중 인스턴스로 확장할 때는 이 배정 맵을 Redis에 게시하고 슬롯 소유자가 읽기만 하도록 바꾸면 된다.
     * 인스턴스마다 따로 계산하게 두면 목록이 갈려 중복 구독과 누락이 함께 발생한다.
     */
    private Map<Integer, List<String>> planSlotAssignments() {
        List<Integer> ownedSlots = connections.keySet().stream().sorted().toList();
        int capacity = ownedSlots.size() * properties.maxSymbolsPerConnection();
        List<String> desired = desiredSymbols(capacity);
        Set<String> desiredSet = Set.copyOf(desired);

        // 체결 완료·화면 이탈·구독 거절로 더 이상 필요 없어진 배정을 놓아준다.
        slotBySymbol.keySet().retainAll(desiredSet);

        Map<Integer, List<String>> plan = new LinkedHashMap<>();
        ownedSlots.forEach(slot -> plan.put(slot, new ArrayList<>()));

        // 1) 기존 배정을 우선순위 순으로 되살린다. 상한을 넘으면 낮은 우선순위부터 자리를 잃는다.
        for (String symbol : desired) {
            Integer slot = slotBySymbol.get(symbol);
            if (slot == null || !plan.containsKey(slot)) {
                continue;
            }
            if (plan.get(slot).size() < properties.maxSymbolsPerConnection()) {
                plan.get(slot).add(symbol);
            } else {
                slotBySymbol.remove(symbol);  // 아래에서 다른 슬롯으로 재배치
            }
        }

        // 2) 새 종목을 여유가 가장 많은 슬롯에 넣는다.
        for (String symbol : desired) {
            if (slotBySymbol.containsKey(symbol)) {
                continue;
            }
            plan.entrySet().stream()
                .filter(entry -> entry.getValue().size() < properties.maxSymbolsPerConnection())
                .min(Comparator.comparingInt(entry -> entry.getValue().size()))
                .ifPresent(entry -> {
                    entry.getValue().add(symbol);
                    slotBySymbol.put(symbol, entry.getKey());
                });
        }

        return plan;
    }

    /**
     * WebSocket으로 받고 싶은 종목을 우선순위 순으로 {@code capacity}개까지.
     *
     * <p>미체결 주문 종목이 화면 구독 종목보다 앞선다. 정원이 모자라면 화면 종목이 먼저 밀려나고,
     * 밀려난 종목은 REST 폴링이 가져간다. 화면 호가는 지연돼도 되지만 체결은 신선한 호가가 필요하기 때문이다.
     */
    private List<String> desiredSymbols(int capacity) {
        Set<String> rejected = rejectedSymbolRegistry.rejectedSymbols();
        List<String> desired = new ArrayList<>();
        // orderedActiveSymbols()는 미체결 주문 종목을 앞에, 구독 종목을 뒤에 놓는다.
        for (String symbol : activeSymbolRegistry.orderedActiveSymbols()) {
            if (desired.size() >= capacity) {
                break;
            }
            if (!rejected.contains(symbol)) {
                desired.add(symbol);
            }
        }
        return desired;
    }

    /**
     * WebSocket이 채워주기로 한 종목. 폴링 제외 판단에 쓰인다.
     *
     * <p>직전 배정 결과를 그대로 돌려준다. 폴링 스레드에서 호출되므로 여기서 다시 계산하지 않는다.
     *
     * <p>주의: 이건 "구독하기로 한 종목"이지 "지금 프레임을 받고 있는 종목"이 아니다.
     * 연결이 끊긴 동안에도 covered로 남는 것은 의도한 동작으로, 캐시가 신선도 임계값을 넘기면
     * 폴링이 자동으로 폴백한다. ACK 확인을 기준으로 삼으면 재연결 때마다 전 종목이 한꺼번에
     * 폴링 대상이 되어 REST 예산이 터진다.
     */
    public List<String> coveredSymbols() {
        return coveredSnapshot;
    }

    /** 실제로 이 인스턴스가 구독을 선언해 둔 종목. 폴링 제외 판단이 아니라 관측용이다. */
    public Set<String> declaredSymbols() {
        Set<String> declared = new HashSet<>();
        for (TossOrderBookWebSocketConnection connection : connections.values()) {
            declared.addAll(connection.declaredSymbols());
        }
        return declared;
    }

    private TossOrderBookWebSocketConnection newConnection(int slotIndex) {
        return new TossOrderBookWebSocketConnection(
            slotIndex,
            properties,
            accessTokenProvider,
            orderBookService,
            rejectedSymbolRegistry,
            objectMapper,
            httpClient
        );
    }

    private void closeSlot(int slotIndex) {
        TossOrderBookWebSocketConnection connection = connections.remove(slotIndex);
        if (connection != null) {
            connection.close();
        }
    }

    private void releaseAll() {
        for (Integer slotIndex : List.copyOf(connections.keySet())) {
            closeSlot(slotIndex);
            slotLock.release(slotIndex);
        }
    }

    @PreDestroy
    public void shutdown() {
        releaseAll();
        // 이후 배정 틱이 돌지 않으므로 여기서 직접 비운다.
        declaredSymbolRegistry.replaceAll(Set.of());
    }
}
