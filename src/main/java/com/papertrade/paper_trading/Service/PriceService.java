package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossPriceClient;
import com.papertrade.paper_trading.Config.PriceCacheProperties;
import com.papertrade.paper_trading.Config.RedisPubSubConfig;
import com.papertrade.paper_trading.Dto.PricePubSubMessage;
import com.papertrade.paper_trading.Dto.PriceResponse;
import com.papertrade.paper_trading.Dto.PriceResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class PriceService {

    private static final int MAX_SYMBOL_COUNT = 200;
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9.\\-]+$");
    private static final String PRICE_CACHE_KEY_PREFIX = "price:";
    private static final String PRICE_POLL_LOCK_KEY_PREFIX = "price:poll-lock:";
    private static final String LOCK_VALUE = "1";

    private final TossPriceClient tossPriceClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final JsonMapper jsonMapper;
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
            fetchCacheAndPublish(pollingSymbols.subList(fromIndex, toIndex));
        }
    }

    private PriceResponse fetchCacheAndPublish(List<String> symbols) {
        PriceResponse response = tossPriceClient.getPrices(symbols);
        for (PriceResult price : results(response)) {
            cachePrice(price);
            publishPrice(price);
        }
        return response;
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
            return jsonMapper.readValue(cachedValue, PriceResult.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void cachePrice(PriceResult price) {
        try {
            stringRedisTemplate.opsForValue().set(
                cacheKey(price.symbol()),
                jsonMapper.writeValueAsString(price),
                Duration.ofSeconds(cacheProperties.ttlSeconds())
            );
        } catch (RuntimeException ignored) {
        }
    }

    private void publishPrice(PriceResult price) {
        try {
            PricePubSubMessage message = new PricePubSubMessage(price.symbol(), price);
            stringRedisTemplate.convertAndSend(
                RedisPubSubConfig.PRICE_UPDATES_CHANNEL,
                jsonMapper.writeValueAsString(message)
            );
        } catch (RuntimeException ignored) {
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
