package com.papertrade.paper_trading.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossCandleClient;
import com.papertrade.paper_trading.Client.TossPriceClient;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import com.papertrade.paper_trading.Config.PriceCacheProperties;
import com.papertrade.paper_trading.Config.RedisPubSubConfig;
import com.papertrade.paper_trading.Dto.Candle;
import com.papertrade.paper_trading.Dto.CandleResponse;
import com.papertrade.paper_trading.Dto.PricePubSubMessage;
import com.papertrade.paper_trading.Dto.PriceResponse;
import com.papertrade.paper_trading.Dto.PriceResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class PriceService {

    /** 토스 현재가 API가 한 요청에 받는 최대 종목 수. */
    public static final int MAX_SYMBOL_COUNT = 200;
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-]+$");
    private static final String PRICE_CACHE_KEY_PREFIX = "price:";
    private static final String PRICE_POLL_LOCK_KEY_PREFIX = "price:poll-lock:";
    private static final String LOCK_VALUE = "1";

    private final TossPriceClient tossPriceClient;
    private final TossCandleClient tossCandleClient;
    private final TossApiRateLimiter tossApiRateLimiter;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final PriceCacheProperties cacheProperties;

    public PriceResponse getPrices(List<String> symbols) {
        List<String> normalizedSymbols = normalizeSymbols(symbols, true);
        Map<String, PriceResult> pricesBySymbol = new LinkedHashMap<>();
        List<String> missedSymbols = new ArrayList<>();

        for (String symbol : normalizedSymbols) {
            PriceResult cachedPrice = getCachedPrice(symbol);
            if (cachedPrice == null) {
                missedSymbols.add(symbol);
            } else {
                pricesBySymbol.put(symbol, cachedPrice);
            }
        }

        if (!missedSymbols.isEmpty()) {
            PriceResponse fetchedResponse = fetchCacheAndPublish(missedSymbols);
            for (PriceResult price : results(fetchedResponse)) {
                pricesBySymbol.put(price.symbol(), price);
            }
        }

        return new PriceResponse(
            normalizedSymbols.stream()
                .map(pricesBySymbol::get)
                .filter(price -> price != null)
                .toList()
        );
    }

    /**
     * 주문 접수에 쓸 현재가. 구하지 못하면 비어 있다 — 호출자는 주문을 접수 검증 대기로 받는다.
     *
     * <p>캐시를 먼저 본다. 캐시 키는 {@code price.cache.ttl-seconds}가 지나면 사라지므로, 키가 있다는 것은
     * 그 시간 안에 받은 값이라는 뜻이다. 주문이 허용하는 현재가의 나이가 곧 이 TTL이다.
     *
     * <p>캐시에 없으면 현재가 API가 아니라 <b>일봉 종가</b>로 한 종목만 가져온다. 현재가 API는 호가와 같은
     * 예산(MARKET_DATA)을 쓰므로, 주문이 몰릴 때마다 부르면 매칭에 쓸 호가 조회를 밀어낸다. 일봉은 별도
     * 예산(MARKET_DATA_CHART)이고, 정규장 중에는 종가가 현재가와 같다(2026-10-05 실측).
     *
     * <p>어떤 이유로 실패하든 예외를 퍼뜨리지 않는다. 예산 소진, 타임아웃, 공급자 오류 모두 "지금은 현재가를
     * 모른다"는 같은 결론이고, 그 경우에도 주문은 받아야 하기 때문이다.
     */
    public Optional<BigDecimal> findPriceForOrder(String symbol) {
        validateSymbol(symbol);

        PriceResult cachedPrice = getCachedPrice(symbol);
        if (hasPrice(cachedPrice)) {
            return Optional.of(cachedPrice.lastPrice());
        }

        try {
            PriceResult fetchedPrice = fetchViaDailyCandle(symbol);
            return hasPrice(fetchedPrice) ? Optional.of(fetchedPrice.lastPrice()) : Optional.empty();
        } catch (RuntimeException e) {
            log.info("Accepting an order without a current price. symbol={}, reason={}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    public void refreshAndPublish(List<String> symbols) {
        List<String> normalizedSymbols = normalizeSymbols(symbols, false);
        if (normalizedSymbols.isEmpty()) {
            return;
        }

        List<String> pollingSymbols = normalizedSymbols.stream()
            .filter(this::tryAcquirePollingLock)
            .toList();

        if (pollingSymbols.isEmpty()) {
            return;
        }

        for (int fromIndex = 0; fromIndex < pollingSymbols.size(); fromIndex += MAX_SYMBOL_COUNT) {
            int toIndex = Math.min(fromIndex + MAX_SYMBOL_COUNT, pollingSymbols.size());
            try {
                fetchCacheAndPublish(pollingSymbols.subList(fromIndex, toIndex));
            } catch (TossApiQuotaUnavailableException ignored) {
                return;
            }
        }
    }

    private PriceResponse fetchCacheAndPublish(List<String> symbols) {
        if (!tossApiRateLimiter.tryAcquire(TossApiRateLimiter.MARKET_DATA_GROUP)) {
            throw new TossApiQuotaUnavailableException(
                TossApiRateLimiter.MARKET_DATA_GROUP,
                tossApiRateLimiter.secondsUntilAvailable(TossApiRateLimiter.MARKET_DATA_GROUP),
                "일시적으로 현재가를 가져올 수 없습니다."
            );
        }
        PriceResponse response = tossPriceClient.getPrices(symbols);
        for (PriceResult price : results(response)) {
            cachePrice(price);
            publishPrice(price);
        }
        return response;
    }

    /**
     * 일봉 종가를 현재가로 쓴다. 한 번에 한 종목뿐이라 일괄 조회에는 쓰지 않는다.
     *
     * <p>일봉의 {@code timestamp}는 체결 시각이 아니라 일봉의 기준 시각이므로 받은 시각을 대신 기록한다.
     */
    private PriceResult fetchViaDailyCandle(String symbol) {
        if (!tossApiRateLimiter.tryAcquire(TossApiRateLimiter.MARKET_DATA_CHART_GROUP)) {
            throw new TossApiQuotaUnavailableException(
                TossApiRateLimiter.MARKET_DATA_CHART_GROUP,
                tossApiRateLimiter.secondsUntilAvailable(TossApiRateLimiter.MARKET_DATA_CHART_GROUP),
                "일시적으로 현재가를 가져올 수 없습니다."
            );
        }
        Candle candle = latestCandle(tossCandleClient.getLatestDailyCandle(symbol));
        if (candle == null || candle.closePrice() == null) {
            return null;
        }

        PriceResult price = new PriceResult(symbol, OffsetDateTime.now(), candle.closePrice(), candle.currency());
        cachePrice(price);
        publishPrice(price);
        return price;
    }

    private Candle latestCandle(CandleResponse candleResponse) {
        if (candleResponse == null
            || candleResponse.result() == null
            || candleResponse.result().candles() == null
            || candleResponse.result().candles().isEmpty()) {
            return null;
        }
        return candleResponse.result().candles().getFirst();
    }

    private boolean hasPrice(PriceResult price) {
        return price != null && price.lastPrice() != null && price.lastPrice().signum() > 0;
    }

    private void validateSymbol(String symbol) {
        if (symbol == null || !SYMBOL_PATTERN.matcher(symbol).matches()) {
            throw new IllegalArgumentException("symbol 형식이 올바르지 않습니다: " + symbol);
        }
    }

    private List<PriceResult> results(PriceResponse response) {
        if (response == null || response.result() == null) {
            return List.of();
        }
        return response.result();
    }

    private PriceResult getCachedPrice(String symbol) {
        try {
            String cachedValue = stringRedisTemplate.opsForValue().get(cacheKey(symbol));
            if (cachedValue == null || cachedValue.isBlank()) {
                return null;
            }
            return objectMapper.readValue(cachedValue, PriceResult.class);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private void cachePrice(PriceResult price) {
        try {
            stringRedisTemplate.opsForValue().set(
                cacheKey(price.symbol()),
                objectMapper.writeValueAsString(price),
                Duration.ofSeconds(cacheProperties.ttlSeconds())
            );
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private void publishPrice(PriceResult price) {
        try {
            PricePubSubMessage message = new PricePubSubMessage(price.symbol(), price);
            stringRedisTemplate.convertAndSend(
                RedisPubSubConfig.PRICE_UPDATES_CHANNEL,
                objectMapper.writeValueAsString(message)
            );
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private boolean tryAcquirePollingLock(String symbol) {
        try {
            Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(
                PRICE_POLL_LOCK_KEY_PREFIX + symbol,
                LOCK_VALUE,
                Duration.ofMillis(cacheProperties.pollingLockTtlMs())
            );
            return Boolean.TRUE.equals(acquired);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private List<String> normalizeSymbols(List<String> symbols, boolean enforceMaxSymbolCount) {
        if (symbols == null || symbols.isEmpty()) {
            if (enforceMaxSymbolCount) {
                throw new IllegalArgumentException("symbols는 1개 이상이어야 합니다.");
            }
            return List.of();
        }

        List<String> normalizedSymbols = symbols.stream()
            .map(String::trim)
            .filter(symbol -> !symbol.isBlank())
            .distinct()
            .toList();

        if (normalizedSymbols.isEmpty()) {
            if (enforceMaxSymbolCount) {
                throw new IllegalArgumentException("symbols는 1개 이상이어야 합니다.");
            }
            return List.of();
        }

        if (enforceMaxSymbolCount && normalizedSymbols.size() > MAX_SYMBOL_COUNT) {
            throw new IllegalArgumentException("symbols는 최대 200개까지 조회할 수 있습니다.");
        }

        for (String symbol : normalizedSymbols) {
            if (!SYMBOL_PATTERN.matcher(symbol).matches()) {
                throw new IllegalArgumentException("symbol 형식이 올바르지 않습니다: " + symbol);
            }
        }

        return normalizedSymbols;
    }

    private String cacheKey(String symbol) {
        return PRICE_CACHE_KEY_PREFIX + symbol;
    }
}
