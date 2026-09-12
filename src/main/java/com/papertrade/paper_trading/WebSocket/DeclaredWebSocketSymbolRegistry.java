package com.papertrade.paper_trading.WebSocket;

import com.papertrade.paper_trading.Service.OrderBookService;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * WebSocket이 지금 구독을 선언해 둔 종목.
 *
 * <p>{@link TossOrderBookWebSocketManager}가 쓰고 {@link OrderBookService}가 읽는다. 서비스가 매니저를
 * 직접 참조하면 순환 참조가 되므로(매니저와 connection이 이미 서비스를 주입받는다) 상태를 이 빈으로 뒤집는다 —
 * {@link RejectedWebSocketSymbolRegistry}와 같은 구조다.
 *
 * <p><b>{@link TossOrderBookWebSocketManager#coveredSymbols()}와 다르다.</b> 저쪽은 "구독하기로 한 종목"이라
 * 연결이 끊긴 동안에도 남지만(폴링에는 신선도 임계값이라는 백스톱이 있으므로 의도된 동작이다), 이쪽은 선언이
 * 실제로 살아 있는 동안만 남는다. 매칭은 백스톱이 없어서 이 구분에 의존한다.
 */
@Component
public class DeclaredWebSocketSymbolRegistry {

    private volatile Set<String> declaredSymbols = Set.of();

    /** 매니저의 배정 틱에서 호출한다. 부분 갱신이 아니라 항상 전체 교체다. */
    public void replaceAll(Collection<String> symbols) {
        this.declaredSymbols = Set.copyOf(symbols);
    }

    public boolean isDeclared(String symbol) {
        return declaredSymbols.contains(symbol);
    }

    /** 관측용. */
    public Set<String> declaredSymbols() {
        return declaredSymbols;
    }
}
