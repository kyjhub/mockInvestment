package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.OrderSubmittedEvent;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderSubmittedStreamPublisher {

    public static final String ORDER_SUBMITTED_STREAM_KEY = "orders:submitted";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${matching-engine.stream.max-length:1000000}")
    private long streamMaxLength;

    public void publish(OrderSubmittedEvent event) {
        // <Stream Key의 타입, Map Key의 타입, Map Value의 타입>
        MapRecord<String, String, String> record = StreamRecords.newRecord()
            .in(ORDER_SUBMITTED_STREAM_KEY)
            .ofMap(Map.of(
                "orderId", event.orderId().toString(),
                "symbol", event.symbol()
            ));
        stringRedisTemplate.opsForStream().add(record, XAddOptions.maxlen(streamMaxLength).approximateTrimming(true));
    }
}
