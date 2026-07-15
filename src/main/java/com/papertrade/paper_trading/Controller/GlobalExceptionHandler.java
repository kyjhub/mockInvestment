package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Client.TossOpenApiException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(MethodArgumentNotValidException exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", "Invalid request"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolationException(ConstraintViolationException exception) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of("message", "Invalid request"));
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
}
