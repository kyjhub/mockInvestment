package com.papertrade.paper_trading.Service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 스트림 레코드 종류마다 ACK 정책이 다르다.
 * 잘못 ACK하면 신호가 사라지고, 잘못 ACK하지 않으면 컨슈머가 막힌다.
 */
class MatchingEngineStreamConsumerTests {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final SymbolMatchingProcessor processor = mock(SymbolMatchingProcessor.class);
    private final DirtyOrderBookSymbolRegistry dirtyRegistry = mock(DirtyOrderBookSymbolRegistry.class);

    private final MatchingEngineStreamConsumer consumer =
        new MatchingEngineStreamConsumer(redisTemplate, processor, dirtyRegistry);

    @Test
    void handsOrderBookTriggersToDirtySetInsteadOfMatchingThem() {
        // 배포 전에 쌓인 backlog와 롤링 배포 중 구버전이 발행한 트리거가 여기로 온다.
        // 그냥 버리면 신호가 사라지므로 dirty set에 넘기고 배출한다.
        consumer.processRecord(record("AAPL", "ORDER_BOOK_UPDATED"));

        verify(dirtyRegistry).markDirty("AAPL");
        verify(processor, never()).process(anyString());
        verifyAcknowledged();
    }

    @Test
    void keepsRecordInPelWhenLockIsBusyButAlsoLeavesAFastRetrySignal() {
        when(processor.process("AAPL")).thenReturn(SymbolMatchingResult.LOCK_BUSY);

        consumer.processRecord(record("AAPL", "ORDER_SUBMITTED"));

        // ACK하지 않아야 PEL 복구가 최종 보장을 유지한다.
        verify(redisTemplate.opsForStream(), never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        // dirty set은 PEL의 5초를 기다리지 않게 하는 보조 신호다.
        verify(dirtyRegistry).markDirty("AAPL");
    }

    @Test
    void acknowledgesWhenQuotaIsUnavailableSoTheRecordDoesNotReachDlq() {
        // 예산 부족은 실패가 아니라 대기다. 재시도 카운트를 올리면 DLQ가 인프라 상태로 오염된다.
        when(processor.process("AAPL")).thenReturn(SymbolMatchingResult.QUOTA_UNAVAILABLE);

        consumer.processRecord(record("AAPL", "ORDER_SUBMITTED"));

        verifyAcknowledged();
    }

    private void verifyAcknowledged() {
        verify(redisTemplate.opsForStream()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    private MapRecord<String, Object, Object> record(String symbol, String reason) {
        return StreamRecords.<String, Object, Object>mapBacked(Map.of("symbol", symbol, "reason", reason))
            .withStreamKey(SymbolMatchRequestedStreamPublisher.SYMBOL_MATCH_REQUESTED_STREAM_KEY)
            .withId(RecordId.of("1-0"));
    }
}
