package com.papertrade.paper_trading.WebSocket;

import com.papertrade.paper_trading.Dto.PricePubSubMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class PriceRedisSubscriber implements MessageListener {

    private static final String PRICE_TOPIC_PREFIX = "/topic/prices/";

    private final SimpMessagingTemplate messagingTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            PricePubSubMessage pubSubMessage = jsonMapper.readValue(
                message.getBody(),
                PricePubSubMessage.class
            );
            messagingTemplate.convertAndSend(
                PRICE_TOPIC_PREFIX + pubSubMessage.symbol(),
                pubSubMessage.price()
            );
        } catch (RuntimeException ignored) {
        }
    }
}
