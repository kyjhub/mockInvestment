package com.papertrade.paper_trading.WebSocket;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

@Component
@RequiredArgsConstructor
public class PriceSubscriptionEventListener {

    private static final Pattern PRICE_DESTINATION_PATTERN =
        Pattern.compile("^/topic/prices/([A-Za-z0-9.\\-]+)$");

    private final PriceSubscriptionRegistry subscriptionRegistry;

    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String destination = accessor.getDestination();
        if (sessionId == null || destination == null) {
            return;
        }

        Matcher matcher = PRICE_DESTINATION_PATTERN.matcher(destination);
        if (matcher.matches()) {
            String subscriptionId = accessor.getSubscriptionId();
            if (subscriptionId != null) {
                subscriptionRegistry.subscribe(sessionId, subscriptionId, matcher.group(1));
            }
        }
    }

    @EventListener
    public void handleUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        if (sessionId != null && subscriptionId != null) {
            subscriptionRegistry.unsubscribe(sessionId, subscriptionId);
        }
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        if (sessionId != null) {
            subscriptionRegistry.disconnect(sessionId);
        }
    }
}
