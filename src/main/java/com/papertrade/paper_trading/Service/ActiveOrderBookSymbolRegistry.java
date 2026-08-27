package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Config.OrderBookActiveSymbolProperties;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import com.papertrade.paper_trading.WebSocket.OrderBookSubscriptionRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * WebSocket 연결을 잡은 인스턴스는 자기 구독자뿐 아니라 전체 인스턴스의 구독 종목을 알아야 한다.
 * {@link OrderBookSubscriptionRegistry}는 인스턴스 로컬이므로, 각 인스턴스가 자기 구독 종목을
 * Redis sorted set에 주기적으로 써 넣고 여기서 합쳐 읽는다.
 */
@Service
@RequiredArgsConstructor
public class ActiveOrderBookSymbolRegistry {

    private static final String ACTIVE_SYMBOLS_KEY = "orderbook:active-symbols";
    private static final List<OrderStatus> MATCHABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final OrderBookSubscriptionRegistry subscriptionRegistry;
    private final OrderRepository orderRepository;
    private final StockRepository stockRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final OrderBookActiveSymbolProperties properties;

    /** 이 인스턴스의 구독 종목을 전역 집합에 등록한다. score는 마지막으로 살아있음을 알린 시각. */
    @Scheduled(fixedDelayString = "${orderbook.active-symbols.refresh-ms:5000}")
    public void publishLocalSubscriptions() {
        long now = System.currentTimeMillis();
        try {
            for (String symbol : subscriptionRegistry.activeSymbols()) {
                stringRedisTemplate.opsForZSet().add(ACTIVE_SYMBOLS_KEY, symbol, now);
            }
            // 어떤 인스턴스도 갱신하지 않은 종목은 자연 소멸시킨다.
            stringRedisTemplate.opsForZSet().removeRangeByScore(
                ACTIVE_SYMBOLS_KEY,
                Double.NEGATIVE_INFINITY,
                now - properties.ttlMs()
            );
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * WebSocket 슬롯 배정용 전역 활성 종목. 미체결 주문 종목이 앞에 오고 구독 종목이 뒤에 온다.
     * 이 순서는 종류를 가르는 기준이 아니라, 슬롯이 모자랄 때 누구를 REST 폴백으로 밀어낼지 정하는 규칙이다.
     */
    public List<String> orderedActiveSymbols() {
        Set<String> ordered = new LinkedHashSet<>(pendingOrderSymbols());
        ordered.addAll(subscribedSymbols());
        return List.copyOf(ordered);
    }

    /** 미체결 주문이 있는 종목. DB 기준이라 인스턴스와 무관하게 전역이다. */
    public List<String> pendingOrderSymbols() {
        List<Long> stockIds = orderRepository.findDistinctStockIdsByStatusIn(MATCHABLE_STATUSES);
        if (stockIds.isEmpty()) {
            return List.of();
        }

        List<String> symbols = new ArrayList<>();
        // 종목별 findById 반복은 N+1이 된다. 폴링과 WebSocket 배정이 주기적으로 호출하므로 한 번에 조회한다.
        for (Stock stock : stockRepository.findAllById(stockIds)) {
            symbols.add(stock.getSymbol());
        }
        Collections.sort(symbols);
        return symbols;
    }

    /** 전체 인스턴스의 구독 종목 합집합. */
    public List<String> subscribedSymbols() {
        try {
            Set<String> symbols = stringRedisTemplate.opsForZSet().rangeByScore(
                ACTIVE_SYMBOLS_KEY,
                System.currentTimeMillis() - properties.ttlMs(),
                Double.POSITIVE_INFINITY
            );
            if (symbols == null || symbols.isEmpty()) {
                return List.of();
            }
            List<String> sorted = new ArrayList<>(symbols);
            Collections.sort(sorted);
            return sorted;
        } catch (RuntimeException ignored) {
            // Redis를 못 읽으면 최소한 이 인스턴스가 아는 것만이라도 돌려준다.
            List<String> local = new ArrayList<>(subscriptionRegistry.activeSymbols());
            Collections.sort(local);
            return local;
        }
    }
}
