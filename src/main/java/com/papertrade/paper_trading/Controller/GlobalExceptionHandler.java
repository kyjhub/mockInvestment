package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Client.TossOpenApiException;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Client.TossApiRateLimiter;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 예산이 소진돼도 202 pending으로 응답할 수 있는 그룹.
     * 판정 기준은 그룹 이름이 아니라 "WebSocket 푸시 채널로 실제 데이터가 전달되는가"다.
     * market-info(장 운영정보)는 푸시 채널이 없어서 503 + Retry-After를 유지한다.
     */
    private static final Set<String> PUSH_CHANNEL_GROUPS = Set.of(
        TossApiRateLimiter.MARKET_DATA_GROUP,
        TossApiRateLimiter.MARKET_DATA_CHART_GROUP
    );

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", message.isBlank() ? "Invalid request" : message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolationException(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
            .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
            .collect(Collectors.joining(", "));
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", message.isBlank() ? "Invalid request" : message));
    }

    @ExceptionHandler(TossOpenApiException.class)
    public ResponseEntity<Map<String, String>> handleTossOpenApiException(TossOpenApiException exception) {
        Map<String, String> body = new LinkedHashMap<>();
        if (exception.requestId() != null) {
            body.put("requestId", exception.requestId());
        }
        if (exception.code() != null) {
            body.put("code", exception.code());
        }
        body.put("message", exception.getMessage());

        return ResponseEntity
            .status(HttpStatusCode.valueOf(exception.statusCode()))
            .body(body);
    }

    @ExceptionHandler(TossApiQuotaUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleTossApiQuotaUnavailableException(
        TossApiQuotaUnavailableException exception
    ) {
        boolean pendingResponse = PUSH_CHANNEL_GROUPS.contains(exception.group());
        HttpStatus status = pendingResponse ? HttpStatus.ACCEPTED : HttpStatus.SERVICE_UNAVAILABLE;
        Map<String, String> body = pendingResponse
            ? Map.of(
                "status", "pending",
                "message", "잠시 후 실시간 갱신으로 반영됩니다."
            )
            : Map.of("message", exception.getMessage());

        return ResponseEntity
            .status(status)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
            .body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedException(Exception exception) {
        log.error("Unexpected server error", exception);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("message", "서버 오류가 발생했습니다."));
    }
}
