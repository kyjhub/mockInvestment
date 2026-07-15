package com.papertrade.paper_trading.WebSocket;

import com.papertrade.paper_trading.Dto.OrderBookPubSubMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class OrderBookRedisSubscriber implements MessageListener {

    private static final String ORDER_BOOK_TOPIC_PREFIX = "/topic/orderbook/";

    private final SimpMessagingTemplate messagingTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            OrderBookPubSubMessage pubSubMessage = jsonMapper.readValue(
                message.getBody(),
                OrderBookPubSubMessage.class
            );
            messagingTemplate.convertAndSend(
                ORDER_BOOK_TOPIC_PREFIX + pubSubMessage.symbol(),
                pubSubMessage.orderBook()
            );
        } catch (RuntimeException ignored) {
        }
    }
}
