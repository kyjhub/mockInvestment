package com.papertrade.paper_trading.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MatchingEngineStreamConsumer {

    private static final String CONSUMER_GROUP = "matching-engine";
    private static final String CONSUMER_NAME = "matching-engine-1";

    private final StringRedisTemplate stringRedisTemplate;
    private final MatchingEngineTransactionService matchingEngineTransactionService;
    private final SymbolOrderLockService symbolOrderLockService;

    @PostConstruct
    public void initializeConsumerGroup() {
        try {
            Boolean streamExists = stringRedisTemplate.hasKey(OrderSubmittedStreamPublisher.ORDER_SUBMITTED_STREAM_KEY);
            if (!Boolean.TRUE.equals(streamExists)) {
                stringRedisTemplate.opsForStream().add(
                    OrderSubmittedStreamPublisher.ORDER_SUBMITTED_STREAM_KEY,
                    Map.of("type", "bootstrap")
                );
            }
            stringRedisTemplate.opsForStream().createGroup(
                OrderSubmittedStreamPublisher.ORDER_SUBMITTED_STREAM_KEY,
                ReadOffset.from("0-0"),
                CONSUMER_GROUP
            );
        } catch (DataAccessException ignored) {
        }
    }

    @Scheduled(fixedDelayString = "${matching-engine.polling.fixed-delay-ms:100}")
    public void consumeSubmittedOrders() {
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
            Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
            StreamReadOptions.empty().count(10).block(Duration.ofMillis(100)),
            StreamOffset.create(OrderSubmittedStreamPublisher.ORDER_SUBMITTED_STREAM_KEY, ReadOffset.lastConsumed())
        );

        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : records) {
            processRecord(record);
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        if (!value.containsKey("orderId")) {
            acknowledge(record);
            return;
        }

        Long orderId = Long.valueOf(value.get("orderId").toString());
        String symbol = value.get("symbol").toString();
        String lockValue = symbolOrderLockService.acquire(symbol);
        try {
            matchingEngineTransactionService.matchOrder(orderId);
            acknowledge(record);
        } finally {
            symbolOrderLockService.release(symbol, lockValue);
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(
            OrderSubmittedStreamPublisher.ORDER_SUBMITTED_STREAM_KEY,
            CONSUMER_GROUP,
            record.getId()
        );
    }
}
