package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Service.OrderBookService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/orderbook")
public class OrderBookController {

    private final OrderBookService orderBookService;

    public OrderBookController(OrderBookService orderBookService) {
        this.orderBookService = orderBookService;
    }

    @GetMapping
    public OrderBookResponse getOrderBook(
        @RequestParam
        @Pattern(regexp = "^[A-Za-z0-9.\\-]+$")
        String symbol
    ) {
        return orderBookService.getOrderBook(symbol);
    }
}
