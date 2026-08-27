package com.papertrade.paper_trading.WebSocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * 이번 WebSocket 전환은 "수신 data가 REST 호가 응답과 동일한 모양"이라는 가정 위에 서 있다.
 * 그래야 기존 OrderBookResult DTO와 호가 신선도 검증을 그대로 재사용할 수 있다.
 * 토스가 이 모양을 바꾸면 여기서 먼저 깨진다.
 */
class TossOrderBookWebSocketPayloadTests {

    private final ObjectMapper objectMapper = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .build();

    /** 실제 수신 프레임 형태. price/volume은 토스 스펙상 문자열로 내려온다. */
    private static final String ORDER_BOOK_FRAME = """
        {
          "type": "message",
          "topic": "orderbook:us:AAPL",
          "data": {
            "timestamp": "2026-08-25T04:25:47.000+09:00",
            "currency": "USD",
            "asks": [{"price": "311.27", "volume": "120"}],
            "bids": [{"price": "311.24", "volume": "40"}]
          }
        }
        """;

    @Test
    void deserializesPushedOrderBookIntoTheRestDto() throws Exception {
        JsonNode frame = objectMapper.readTree(ORDER_BOOK_FRAME);

        OrderBookResult result = objectMapper.convertValue(frame.path("data"), OrderBookResult.class);

        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.timestamp()).isNotNull();
        assertThat(result.asks()).hasSize(1);
        assertThat(result.asks().get(0).price()).isEqualByComparingTo(new BigDecimal("311.27"));
        assertThat(result.asks().get(0).volume()).isEqualTo(120L);
        assertThat(result.bids().get(0).price()).isEqualByComparingTo(new BigDecimal("311.24"));
        assertThat(result.bids().get(0).volume()).isEqualTo(40L);
    }

    @Test
    void toleratesNullTimestampWhenNoTradeHasOccurred() throws Exception {
        String frame = """
            {"type":"message","topic":"orderbook:us:AAPL",
             "data":{"timestamp":null,"currency":"USD","asks":[],"bids":[]}}
            """;

        JsonNode node = objectMapper.readTree(frame);
        OrderBookResult result = objectMapper.convertValue(node.path("data"), OrderBookResult.class);

        assertThat(result.timestamp()).isNull();
        assertThat(result.asks()).isEmpty();
    }
}
