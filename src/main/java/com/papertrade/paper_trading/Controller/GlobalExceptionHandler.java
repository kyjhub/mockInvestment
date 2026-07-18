package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Client.TossOpenApiException;
import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

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
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedException(Exception exception) {
        log.error("Unexpected server error", exception);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("message", "서버 오류가 발생했습니다."));
    }
}
