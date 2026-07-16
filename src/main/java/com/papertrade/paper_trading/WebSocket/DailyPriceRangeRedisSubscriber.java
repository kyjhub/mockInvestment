package com.papertrade.paper_trading.WebSocket;

import com.papertrade.paper_trading.Dto.DailyPriceRangePubSubMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class DailyPriceRangeRedisSubscriber implements MessageListener {

    private static final String DAILY_PRICE_RANGE_TOPIC_PREFIX = "/topic/daily-price-range/";

    private final SimpMessagingTemplate messagingTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            DailyPriceRangePubSubMessage pubSubMessage = jsonMapper.readValue(
                message.getBody(),
                DailyPriceRangePubSubMessage.class
            );
            messagingTemplate.convertAndSend(
                DAILY_PRICE_RANGE_TOPIC_PREFIX + pubSubMessage.symbol(),
                pubSubMessage.dailyPriceRange()
            );
        } catch (RuntimeException ignored) {
        }
    }
}
