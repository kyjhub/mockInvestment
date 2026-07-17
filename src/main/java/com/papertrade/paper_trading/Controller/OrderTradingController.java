package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Dto.OrderPlaceRequest;
import com.papertrade.paper_trading.Dto.OrderResponse;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Service.OrderTradingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderTradingController {

    private final OrderTradingService orderTradingService;

    @PostMapping
    public OrderResponse placeOrder(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody OrderPlaceRequest request
    ) {
        return orderTradingService.placeOrder(user, request);
    }

    @DeleteMapping("/{orderId}")
    public OrderResponse cancelOrder(
        @AuthenticationPrincipal User user,
        @PathVariable Long orderId
    ) {
        return orderTradingService.cancelOrder(user, orderId);
    }
}
