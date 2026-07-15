package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Dto.PriceResponse;
import com.papertrade.paper_trading.Service.PriceService;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PriceService priceService;

    @GetMapping
    public PriceResponse getPrices(@RequestParam String symbols) {
        List<String> parsedSymbols = Arrays.stream(symbols.split(","))
            .toList();
        return priceService.getPrices(parsedSymbols);
    }
}
