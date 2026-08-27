package com.papertrade.paper_trading.WebSocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.papertrade.paper_trading.Client.TossAccessTokenProvider;
import com.papertrade.paper_trading.Config.TossWebSocketProperties;
import com.papertrade.paper_trading.Dto.OrderBookResult;
import com.papertrade.paper_trading.Service.OrderBookService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 슬롯 하나가 소유하는 토스 WebSocket 연결. 상태 전이는 전부 {@link TossOrderBookWebSocketManager}의
 * 스케줄 틱에서 호출되므로 별도 스레드를 만들지 않는다.
 */
@Slf4j
public class TossOrderBookWebSocketConnection implements WebSocket.Listener {

    private static final long BASE_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BACKOFF_MILLIS = 30_000L;
    /** rate-limit-exceeded를 받으면 약 1초 대기 후 재선언한다 (Retry-After가 제공되지 않는다). */
    private static final long DECLARE_COOLDOWN_MILLIS = 1_000L;

    private final int slotIndex;
    private final TossWebSocketProperties properties;
    private final TossAccessTokenProvider accessTokenProvider;
    private final OrderBookService orderBookService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final StringBuilder textBuffer = new StringBuilder();
    /** stock-not-found 등으로 거부된 종목. 원인을 고치기 전엔 재선언해도 계속 거부되므로 제외한다. */
    private final Set<String> rejectedSymbols = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    private volatile WebSocket webSocket;
    private volatile List<String> declaredSymbols = List.of();
    private volatile List<String> desiredSymbols = List.of();
    private volatile long nextConnectAttemptAt;
    private volatile long nextDeclareAllowedAt;
    private volatile long lastPingAt;
    private int consecutiveFailures;

    public TossOrderBookWebSocketConnection(
        int slotIndex,
        TossWebSocketProperties properties,
        TossAccessTokenProvider accessTokenProvider,
        OrderBookService orderBookService,
        ObjectMapper objectMapper,
        HttpClient httpClient
    ) {
        this.slotIndex = slotIndex;
        this.properties = properties;
        this.accessTokenProvider = accessTokenProvider;
        this.orderBookService = orderBookService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public void setDesiredSymbols(List<String> symbols) {
        this.desiredSymbols = List.copyOf(symbols);
    }

    /** 연결이 없으면 백오프를 지켰는지 확인하고 연결한다. */
    public void ensureConnected() {
        if (webSocket != null || connecting.get()) {
            return;
        }
        if (System.currentTimeMillis() < nextConnectAttemptAt) {
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            return;
        }

        try {
            httpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + accessTokenProvider.accessToken())
                .buildAsync(URI.create(properties.url()), this)
                .whenComplete((socket, throwable) -> {
                    connecting.set(false);
                    if (throwable != null) {
                        log.warn("Toss WebSocket connect failed. slot={}", slotIndex, throwable);
                        scheduleReconnect();
                        return;
                    }
                    log.info("Toss WebSocket connected. slot={}", slotIndex);
                    consecutiveFailures = 0;
                    lastPingAt = System.currentTimeMillis();
                    // 재연결이므로 이전 선언은 무효다. 다음 틱에서 전체를 다시 선언한다.
                    declaredSymbols = List.of();
                });
        } catch (RuntimeException e) {
            connecting.set(false);
            log.warn("Toss WebSocket connect threw. slot={}", slotIndex, e);
            scheduleReconnect();
        }
    }

    /** 담당 종목이 바뀌었을 때만 선언을 보낸다. 이 호출 주기가 곧 디바운스다. */
    public void declareIfChanged() {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        if (System.currentTimeMillis() < nextDeclareAllowedAt) {
            return;
        }

        List<String> target = new ArrayList<>(desiredSymbols);
        target.removeAll(rejectedSymbols);
        if (target.equals(declaredSymbols)) {
            return;
        }

        try {
            socket.sendText(declarationPayload(target), true);
            declaredSymbols = List.copyOf(target);
        } catch (RuntimeException e) {
            log.warn("Toss WebSocket declare failed. slot={}", slotIndex, e);
        }
    }

    /** 서버는 클라이언트로부터의 수신이 180초 없으면 끊는다. 데이터 수신은 이 타이머를 리셋하지 않는다. */
    public void pingIfDue() {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        long intervalMillis = properties.pingIntervalSeconds() * 1_000L;
        if (System.currentTimeMillis() - lastPingAt < intervalMillis) {
            return;
        }
        try {
            socket.sendText("PING", true);
            lastPingAt = System.currentTimeMillis();
        } catch (RuntimeException e) {
            log.warn("Toss WebSocket ping failed. slot={}", slotIndex, e);
        }
    }

    public void close() {
        WebSocket socket = webSocket;
        webSocket = null;
        declaredSymbols = List.of();
        if (socket == null) {
            return;
        }
        try {
            // 재연결 전에 쓰던 연결을 먼저 닫아야 밀어내기가 반복되지 않는다.
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "slot released");
        } catch (RuntimeException ignored) {
        }
    }

    private String declarationPayload(List<String> symbols) {
        try {
            List<Object> declaration = new ArrayList<>();
            declaration.add(java.util.Map.of("id", "slot-" + slotIndex + "-" + System.currentTimeMillis()));
            if (!symbols.isEmpty()) {
                declaration.add(java.util.Map.of(
                    "type", properties.orderBookSubscriptionType(),
                    "codes", symbols
                ));
            }
            return objectMapper.writeValueAsString(declaration);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Toss WebSocket declaration", e);
        }
    }

    // --- WebSocket.Listener ---

    @Override
    public void onOpen(WebSocket socket) {
        this.webSocket = socket;
        socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            String frame = textBuffer.toString();
            textBuffer.setLength(0);
            try {
                handleFrame(frame);
            } catch (RuntimeException e) {
                log.warn("Toss WebSocket frame handling failed. slot={}", slotIndex, e);
            }
        }
        socket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
        log.info("Toss WebSocket closed. slot={}, statusCode={}, reason={}", slotIndex, statusCode, reason);
        webSocket = null;
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
        log.warn("Toss WebSocket error. slot={}", slotIndex, error);
        webSocket = null;
        scheduleReconnect();
    }

    private void handleFrame(String frame) {
        JsonNode node;
        try {
            node = objectMapper.readTree(frame);
        } catch (Exception e) {
            log.warn("Toss WebSocket frame is not valid JSON. slot={}", slotIndex, e);
            return;
        }

        String type = node.path("type").asText("");
        switch (type) {
            case "message" -> handleOrderBookMessage(node);
            case "subscriptions" -> handleSubscriptionsAck(node);
            case "error" -> handleError(node);
            case "pong" -> { }
            default -> log.debug("Toss WebSocket unknown frame type. slot={}, type={}", slotIndex, type);
        }
    }

    private void handleOrderBookMessage(JsonNode node) {
        String symbol = symbolFromTopic(node.path("topic").asText(""));
        if (symbol == null) {
            return;
        }
        OrderBookResult result = objectMapper.convertValue(node.path("data"), OrderBookResult.class);
        if (result == null) {
            return;
        }
        // 캐시·Pub/Sub·매칭 트리거가 REST 경로와 동일하게 이어진다.
        orderBookService.applyOrderBook(symbol, result);
    }

    /** topic은 {@code orderbook:{시장}:{symbol}} 형식이다. */
    private String symbolFromTopic(String topic) {
        int lastSeparator = topic.lastIndexOf(':');
        if (lastSeparator < 0 || lastSeparator == topic.length() - 1) {
            return null;
        }
        return topic.substring(lastSeparator + 1);
    }

    private void handleSubscriptionsAck(JsonNode node) {
        Set<String> confirmed = new LinkedHashSet<>();
        for (JsonNode subscribed : node.path("subscribed")) {
            String symbol = symbolFromTopic(subscribed.asText(""));
            if (symbol != null) {
                confirmed.add(symbol);
            }
        }

        for (JsonNode rejected : node.path("rejected")) {
            String symbol = symbolFromTopic(rejected.path("target").asText(""));
            if (symbol != null) {
                rejectedSymbols.add(symbol);
                log.warn(
                    "Toss WebSocket subscription rejected. slot={}, symbol={}, code={}",
                    slotIndex,
                    symbol,
                    rejected.path("code").asText("")
                );
            }
        }

        // full-replace 구조에서 의도와 서버 상태가 어긋난 것을 잡아낼 수 있는 유일한 지점이다.
        List<String> intended = new ArrayList<>(declaredSymbols);
        intended.removeAll(rejectedSymbols);
        if (!confirmed.containsAll(intended) || confirmed.size() != intended.size()) {
            List<String> missing = new ArrayList<>(intended);
            missing.removeAll(confirmed);
            log.warn(
                "Toss WebSocket subscription mismatch. slot={}, intended={}, confirmed={}, missing={}",
                slotIndex,
                intended.size(),
                confirmed.size(),
                missing
            );
            // 다음 틱에서 다시 선언하도록 강제한다.
            declaredSymbols = List.of();
        }
    }

    private void handleError(JsonNode node) {
        String code = node.path("error").path("code").asText("");
        String message = node.path("error").path("message").asText("");
        log.warn("Toss WebSocket error frame. slot={}, code={}, message={}", slotIndex, code, message);

        switch (code) {
            case "rate-limit-exceeded" -> {
                nextDeclareAllowedAt = System.currentTimeMillis() + DECLARE_COOLDOWN_MILLIS;
                declaredSymbols = List.of();
            }
            case "server-shutdown" -> close();
            // too-many-topics는 선언 전체가 거부된 것이므로 기존 구독이 유지된다. 다음 틱에서 다시 선언한다.
            case "too-many-topics", "too-many" -> declaredSymbols = List.of();
            default -> declaredSymbols = List.of();
        }
    }

    private void scheduleReconnect() {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 30);
        long backoff = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * (1L << (consecutiveFailures - 1)));
        long jitter = ThreadLocalRandom.current().nextLong(backoff + 1L);
        nextConnectAttemptAt = System.currentTimeMillis() + jitter;
        declaredSymbols = List.of();
    }

    public Set<String> rejectedSymbols() {
        return Set.copyOf(rejectedSymbols);
    }

    public List<String> declaredSymbols() {
        return Collections.unmodifiableList(declaredSymbols);
    }
}
