package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Dto.MarketCalendarResponse;
import com.papertrade.paper_trading.Service.MarketCalendarService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-calendar")
@RequiredArgsConstructor
public class MarketCalendarController {

    private final MarketCalendarService marketCalendarService;

    @GetMapping("/US")
    public MarketCalendarResponse getUsMarketCalendar(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        LocalDate date
    ) {
        return marketCalendarService.getUsMarketCalendar(date);
    }
}
