package com.papertrade.paper_trading.WebSocket;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class OrderBookSubscriptionRegistry {

    private final Map<String, Map<String, String>> sessionSubscriptionSymbols = new HashMap<>();
    private final Map<String, Integer> symbolSubscriberCounts = new HashMap<>();

    public synchronized void subscribe(String sessionId, String subscriptionId, String symbol) {
        Map<String, String> subscriptions = sessionSubscriptionSymbols.computeIfAbsent(
            sessionId,
            ignored -> new HashMap<>()
        );
        String previousSymbol = subscriptions.put(subscriptionId, symbol);

        if (symbol.equals(previousSymbol)) {
            return;
        }

        if (previousSymbol != null) {
            decrementSubscriberCount(previousSymbol);
        }
        symbolSubscriberCounts.merge(symbol, 1, Integer::sum);
    }

    public synchronized void unsubscribe(String sessionId, String subscriptionId) {
        Map<String, String> subscriptions = sessionSubscriptionSymbols.get(sessionId);
        if (subscriptions == null) {
            return;
        }

        String symbol = subscriptions.remove(subscriptionId);
        if (symbol != null) {
            decrementSubscriberCount(symbol);
        }

        if (subscriptions.isEmpty()) {
            sessionSubscriptionSymbols.remove(sessionId);
        }
    }

    public synchronized void disconnect(String sessionId) {
        Map<String, String> subscriptions = sessionSubscriptionSymbols.remove(sessionId);
        if (subscriptions == null) {
            return;
        }

        for (String symbol : new HashSet<>(subscriptions.values())) {
            long removedCount = subscriptions.values().stream()
                .filter(symbol::equals)
                .count();
            for (int i = 0; i < removedCount; i++) {
                decrementSubscriberCount(symbol);
            }
        }
    }

    public synchronized Set<String> activeSymbols() {
        return Collections.unmodifiableSet(new HashSet<>(symbolSubscriberCounts.keySet()));
    }

    private void decrementSubscriberCount(String symbol) {
        int subscriberCount = symbolSubscriberCounts.getOrDefault(symbol, 0) - 1;
        if (subscriberCount <= 0) {
            symbolSubscriberCounts.remove(symbol);
        } else {
            symbolSubscriberCounts.put(symbol, subscriberCount);
        }
    }
}
