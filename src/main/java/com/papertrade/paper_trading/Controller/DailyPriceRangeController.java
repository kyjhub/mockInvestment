package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Service.DailyPriceRangeService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/daily-price-range")
@RequiredArgsConstructor
public class DailyPriceRangeController {

    private final DailyPriceRangeService dailyPriceRangeService;

    @GetMapping
    public DailyPriceRangeResponse getDailyPriceRange(
        @RequestParam
        @Pattern(regexp = "^[A-Za-z0-9.\\-]+$")
        String symbol
    ) {
        return dailyPriceRangeService.getDailyPriceRange(symbol);
    }
}
