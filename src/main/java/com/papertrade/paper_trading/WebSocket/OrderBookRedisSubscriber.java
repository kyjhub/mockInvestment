package com.papertrade.paper_trading.WebSocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Dto.OrderBookPubSubMessage;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderBookRedisSubscriber implements MessageListener {

    private static final String ORDER_BOOK_TOPIC_PREFIX = "/topic/orderbook/";

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            OrderBookPubSubMessage pubSubMessage = objectMapper.readValue(
                message.getBody(),
                OrderBookPubSubMessage.class
            );
            messagingTemplate.convertAndSend(
                ORDER_BOOK_TOPIC_PREFIX + pubSubMessage.symbol(),
                pubSubMessage.orderBook()
            );
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
