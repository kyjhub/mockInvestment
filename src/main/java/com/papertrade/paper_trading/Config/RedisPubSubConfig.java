package com.papertrade.paper_trading.Config;

import com.papertrade.paper_trading.WebSocket.OrderBookRedisSubscriber;
import com.papertrade.paper_trading.WebSocket.PriceRedisSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisPubSubConfig {

    public static final String ORDER_BOOK_UPDATES_CHANNEL = "orderbook:updates";
    public static final String PRICE_UPDATES_CHANNEL = "price:updates";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
        RedisConnectionFactory redisConnectionFactory,
        OrderBookRedisSubscriber orderBookRedisSubscriber,
        PriceRedisSubscriber priceRedisSubscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(orderBookRedisSubscriber, new ChannelTopic(ORDER_BOOK_UPDATES_CHANNEL));
        container.addMessageListener(priceRedisSubscriber, new ChannelTopic(PRICE_UPDATES_CHANNEL));
        return container;
    }
}
