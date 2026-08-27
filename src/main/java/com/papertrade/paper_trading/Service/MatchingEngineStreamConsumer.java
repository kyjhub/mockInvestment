package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingEngineStreamConsumer {

    private static final String CONSUMER_GROUP = "matching-engine";
    private static final String RETRY_COUNT_HASH_KEY = "symbols:match-requested:retry-counts";
    private static final String DLQ_STREAM_KEY = "symbols:match-requested:dlq";

    private final StringRedisTemplate stringRedisTemplate;
    private final MatchingEngineTransactionService matchingEngineTransactionService;
    private final SymbolOrderLockService symbolOrderLockService;
    private final OrderBookService orderBookService;
    private final DailyPriceRangeService dailyPriceRangeService;

    @Value("${matching-engine.stream.batch-size:10}")
    private int batchSize;

    @Value("${matching-engine.stream.pending-batch-size:20}")
    private int pendingBatchSize;

    @Value("${matching-engine.stream.pending-min-idle-ms:5000}")
    private long pendingMinIdleMs;

    @Value("${matching-engine.stream.max-retry-count:5}")
    private int maxRetryCount;

    @Value("${matching-engine.stream.consumer-name:${spring.application.name:paper-trading}-${random.uuid}}")
    private String consumerName;

    @Value("${matching-engine.stream.dlq-max-length:100000}")
    private long dlqMaxLength;

    @PostConstruct
    public void initializeConsumerGroup() {
        try {
            Boolean streamExists = stringRedisTemplate.hasKey(
                SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY
            );
            if (!Boolean.TRUE.equals(streamExists)) {
                stringRedisTemplate.opsForStream().add(
                    SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY,
                    Map.of("type", "bootstrap")
                );
            }
            stringRedisTemplate.opsForStream().createGroup(
                SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY,
                ReadOffset.from("0-0"),
                CONSUMER_GROUP
            );
        } catch (DataAccessException ignored) {
        }
    }

    @Scheduled(fixedDelayString = "${matching-engine.polling.fixed-delay-ms:100}")
    public void consumeMatchRequests() {
        // MapRecord<SYMBOL_MATCH_REQUESTED_STREAM_KEY, symbol, reason>
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
            Consumer.from(CONSUMER_GROUP, consumerName),
            StreamReadOptions.empty().count(batchSize).block(Duration.ofMillis(100)),
            StreamOffset.create(
                SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY,
                ReadOffset.lastConsumed()
            )
        );

        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : records) {
            processRecord(record);
        }
    }

    @Scheduled(fixedDelayString = "${matching-engine.stream.pending-recovery-delay-ms:1000}")
    public void recoverPendingOrders() {
        PendingMessages pendingMessages = stringRedisTemplate.opsForStream().pending(
            SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY,
            CONSUMER_GROUP,
            Range.unbounded(),
            pendingBatchSize
        );

        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return;
        }

        List<RecordId> recordIds = pendingMessages.stream()
            .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(Duration.ofMillis(pendingMinIdleMs)) >= 0)
            .map(PendingMessage::getId)
            .toList();

        if (recordIds.isEmpty()) {
            return;
        }

        List<MapRecord<String, Object, Object>> claimedRecords = stringRedisTemplate.opsForStream().claim(
            SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY,
            CONSUMER_GROUP,
            consumerName,
            Duration.ofMillis(pendingMinIdleMs),
            recordIds.toArray(RecordId[]::new)
        );

        if (claimedRecords == null || claimedRecords.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : claimedRecords) {
            processRecord(record);
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        if (!value.containsKey("symbol")) {
            acknowledge(record);
            return;
        }

        String symbol;
        try {
            symbol = value.get("symbol").toString();
            if (symbol.isBlank()) {
                throw new IllegalArgumentException("symbol is blank");
            }
        } catch (Exception e) {
            handleFailure(record, new IllegalArgumentException("Invalid symbol match stream payload", e));
            return;
        }

        String lockValue;
        try {
            lockValue = symbolOrderLockService.acquire(symbol);
        } catch (IllegalArgumentException e) {
            log.debug("Skip matching because symbol lock is busy. recordId={}, symbol={}", record.getId(), symbol);
            return;
        }

        try {
            matchUntilOrderBookVersionIsStable(symbol);
            acknowledge(record);
            clearRetryCount(record);
        } catch (TossApiQuotaUnavailableException e) {
            log.debug("Skip matching because Toss API quota is unavailable. symbol={}", symbol);
            acknowledge(record);
            clearRetryCount(record);
        } catch (Exception e) {
            handleFailure(record, e);
        } finally {
            symbolOrderLockService.release(symbol, lockValue);
        }
    }

    private void matchUntilOrderBookVersionIsStable(String symbol) {
        long versionBeforeMatching;
        long versionAfterMatching;
        do {
            versionBeforeMatching = orderBookService.getOrderBookVersion(symbol);
            DailyPriceRangeResponse dailyPriceRange = dailyPriceRangeService.getDailyPriceRange(symbol);
            matchingEngineTransactionService.matchSymbol(symbol, dailyPriceRange);
            versionAfterMatching = orderBookService.getOrderBookVersion(symbol);
        } while (versionAfterMatching != versionBeforeMatching);
    }

    private void handleFailure(MapRecord<String, Object, Object> record, Exception e) {
        Long retryCount = stringRedisTemplate.opsForHash().increment(
            RETRY_COUNT_HASH_KEY,
            record.getId().getValue(),
            1
        );

        if (retryCount != null && retryCount >= maxRetryCount) {
            moveToDeadLetterQueue(record, e, retryCount);
            acknowledge(record);
            clearRetryCount(record);
            return;
        }

        log.warn(
            "Matching engine failed. recordId={}, retryCount={}, maxRetryCount={}",
            record.getId(),
            retryCount,
            maxRetryCount,
            e
        );
    }

    private void moveToDeadLetterQueue(MapRecord<String, Object, Object> record, Exception e, Long retryCount) {
        Map<String, String> dlqValue = new LinkedHashMap<>();
        dlqValue.put("sourceStream", SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY);
        dlqValue.put("sourceRecordId", record.getId().getValue());
        dlqValue.put("consumerGroup", CONSUMER_GROUP);
        dlqValue.put("consumerName", consumerName);
        dlqValue.put("retryCount", retryCount.toString());
        dlqValue.put("failedAt", OffsetDateTime.now().toString());
        dlqValue.put("exceptionType", e.getClass().getName());
        dlqValue.put("exceptionMessage", e.getMessage() == null ? "" : e.getMessage());

        for (Map.Entry<Object, Object> entry : record.getValue().entrySet()) {
            dlqValue.put("payload." + entry.getKey(), entry.getValue() == null ? "" : entry.getValue().toString());
        }

        stringRedisTemplate.opsForStream().add(
            StreamRecords.newRecord()
                .in(DLQ_STREAM_KEY)
                .ofMap(dlqValue),
            XAddOptions.maxlen(dlqMaxLength).approximateTrimming(true)
        );

        log.error(
            "Matching engine message moved to DLQ. sourceRecordId={}, symbol={}, reason={}, retryCount={}",
            record.getId(),
            payloadValue(record, "symbol"),
            payloadValue(record, "reason"),
            retryCount,
            e
        );
    }

    /** 어느 종목이 실패했는지는 로그만 봐도 알 수 있어야 한다. DLQ를 열어봐야만 알 수 있으면 곤란하다. */
    private String payloadValue(MapRecord<String, Object, Object> record, String key) {
        Object value = record.getValue().get(key);
        return value == null ? "" : value.toString();
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream().acknowledge(
            SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY,
            CONSUMER_GROUP,
            record.getId()
        );
    }

    private void clearRetryCount(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForHash().delete(RETRY_COUNT_HASH_KEY, record.getId().getValue());
    }
}
