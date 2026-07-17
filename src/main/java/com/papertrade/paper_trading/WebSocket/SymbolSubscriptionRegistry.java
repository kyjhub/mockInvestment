package com.papertrade.paper_trading.WebSocket;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SymbolSubscriptionRegistry {

    private final Map<String, Map<String, String>> sessionSubscriptions = new HashMap<>();
    private final Map<String, Set<String>> symbolSubscriptions = new HashMap<>();

    public synchronized void subscribe(String sessionId, String subscriptionId, String symbol) {
        Map<String, String> subscriptions = sessionSubscriptions.computeIfAbsent(
            sessionId,
            ignored -> new HashMap<>()
        );

        String previousSymbol = subscriptions.put(subscriptionId, symbol);
        String subscriberKey = subscriberKey(sessionId, subscriptionId);
        if (previousSymbol != null && !previousSymbol.equals(symbol)) {
            removeSymbolSubscription(previousSymbol, subscriberKey);
        }
        symbolSubscriptions.computeIfAbsent(symbol, ignored -> new HashSet<>()).add(subscriberKey);
    }

    public synchronized void unsubscribe(String sessionId, String subscriptionId) {
        Map<String, String> subscriptions = sessionSubscriptions.get(sessionId);
        if (subscriptions == null) {
            return;
        }

        String symbol = subscriptions.remove(subscriptionId);
        if (symbol != null) {
            removeSymbolSubscription(symbol, subscriberKey(sessionId, subscriptionId));
        }
        if (subscriptions.isEmpty()) {
            sessionSubscriptions.remove(sessionId);
        }
    }

    public synchronized void disconnect(String sessionId) {
        Map<String, String> subscriptions = sessionSubscriptions.remove(sessionId);
        if (subscriptions == null) {
            return;
        }

        subscriptions.forEach((subscriptionId, symbol) ->
            removeSymbolSubscription(symbol, subscriberKey(sessionId, subscriptionId))
        );
    }

    public synchronized Set<String> activeSymbols() {
        return Collections.unmodifiableSet(new HashSet<>(symbolSubscriptions.keySet()));
    }

    private void removeSymbolSubscription(String symbol, String subscriberKey) {
        Set<String> subscribers = symbolSubscriptions.get(symbol);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(subscriberKey);
        if (subscribers.isEmpty()) {
            symbolSubscriptions.remove(symbol);
        }
    }

    private String subscriberKey(String sessionId, String subscriptionId) {
        return sessionId + ":" + subscriptionId;
    }
}
