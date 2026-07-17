package com.papertrade.paper_trading.WebSocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SymbolSubscriptionRegistryTests {

    private final SymbolSubscriptionRegistry registry = new SymbolSubscriptionRegistry();

    @Test
    void tracksMultipleSubscriptionsForTheSameSymbolIndependently() {
        registry.subscribe("session-1", "subscription-1", "AAPL");
        registry.subscribe("session-1", "subscription-2", "AAPL");

        registry.unsubscribe("session-1", "subscription-1");

        assertThat(registry.activeSymbols()).containsExactly("AAPL");

        registry.unsubscribe("session-1", "subscription-2");

        assertThat(registry.activeSymbols()).isEmpty();
    }

    @Test
    void movesAnExistingSubscriptionToItsNewSymbol() {
        registry.subscribe("session-1", "subscription-1", "AAPL");
        registry.subscribe("session-1", "subscription-1", "MSFT");

        assertThat(registry.activeSymbols()).containsExactly("MSFT");
    }

    @Test
    void disconnectRemovesEverySubscriptionOwnedByTheSession() {
        registry.subscribe("session-1", "subscription-1", "AAPL");
        registry.subscribe("session-1", "subscription-2", "MSFT");
        registry.subscribe("session-2", "subscription-1", "AAPL");

        registry.disconnect("session-1");

        assertThat(registry.activeSymbols()).containsExactly("AAPL");
    }
}
