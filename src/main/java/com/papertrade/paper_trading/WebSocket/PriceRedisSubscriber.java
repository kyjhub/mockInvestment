package com.papertrade.paper_trading.WebSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Dto.PricePubSubMessage;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PriceRedisSubscriber implements MessageListener {

    private static final String PRICE_TOPIC_PREFIX = "/topic/prices/";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            PricePubSubMessage pubSubMessage = objectMapper.readValue(
                message.getBody(),
                PricePubSubMessage.class
            );
            messagingTemplate.convertAndSend(
                PRICE_TOPIC_PREFIX + pubSubMessage.symbol(),
                pubSubMessage.price()
            );
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
