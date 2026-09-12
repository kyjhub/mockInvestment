package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Dto.AccountBalanceResponse;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Service.AccountQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountQueryService accountQueryService;

    @GetMapping("/me/balance")
    public AccountBalanceResponse getBalance(@AuthenticationPrincipal User user) {
        return accountQueryService.getBalance(user);
    }
}
