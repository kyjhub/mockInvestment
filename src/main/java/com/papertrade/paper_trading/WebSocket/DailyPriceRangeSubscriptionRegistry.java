package com.papertrade.paper_trading.WebSocket;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DailyPriceRangeSubscriptionRegistry {

    private final Map<String, Map<String, String>> sessionSymbolSubscriptions = new HashMap<>();
    private final Map<String, Integer> symbolSubscriberCounts = new HashMap<>();

    public synchronized void subscribe(String sessionId, String subscriptionId, String symbol) {
        Map<String, String> symbolSubscriptions = sessionSymbolSubscriptions.computeIfAbsent(
            sessionId,
            ignored -> new HashMap<>()
        );

        String previousSubscriptionId = symbolSubscriptions.put(symbol, subscriptionId);
        if (previousSubscriptionId == null) {
            symbolSubscriberCounts.merge(symbol, 1, Integer::sum);
        }
    }

    public synchronized void unsubscribe(String sessionId, String subscriptionId) {
        Map<String, String> symbolSubscriptions = sessionSymbolSubscriptions.get(sessionId);
        if (symbolSubscriptions == null) {
            return;
        }

        String symbol = findSymbolBySubscriptionId(symbolSubscriptions, subscriptionId);
        if (symbol != null) {
            symbolSubscriptions.remove(symbol);
            decrementSubscriberCount(symbol);
        }

        if (symbolSubscriptions.isEmpty()) {
            sessionSymbolSubscriptions.remove(sessionId);
        }
    }

    public synchronized void disconnect(String sessionId) {
        Map<String, String> symbolSubscriptions = sessionSymbolSubscriptions.remove(sessionId);
        if (symbolSubscriptions == null) {
            return;
        }

        for (String symbol : symbolSubscriptions.keySet()) {
            decrementSubscriberCount(symbol);
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

    private String findSymbolBySubscriptionId(Map<String, String> symbolSubscriptions, String subscriptionId) {
        return symbolSubscriptions.entrySet().stream()
            .filter(entry -> subscriptionId.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }
}
