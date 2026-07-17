package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.SymbolMatchRequestedEvent;
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
public class SymbolMatchRequestedStreamPublisher {

    public static final String SYMBOL_MATCH_REQUESTED_STREAM_KEY = "symbols:match-requested";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${matching-engine.stream.max-length:1000000}")
    private long streamMaxLength;

    public void publish(SymbolMatchRequestedEvent event) {
        MapRecord<String, String, String> record = StreamRecords.newRecord()
            .in(SYMBOL_MATCH_REQUESTED_STREAM_KEY)
            .ofMap(Map.of(
                "symbol", event.symbol(),
                "reason", event.reason()
            ));
        stringRedisTemplate.opsForStream().add(
            record,
            XAddOptions.maxlen(streamMaxLength).approximateTrimming(true)
        );
    }
}
