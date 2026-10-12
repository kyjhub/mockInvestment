package com.papertrade.paper_trading.Controller;

import com.papertrade.paper_trading.Dto.LedgerCacheRebuildResult;
import com.papertrade.paper_trading.Dto.LedgerCorrectionRequest;
import com.papertrade.paper_trading.Dto.LedgerReversalResult;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Service.LedgerCorrectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원장 대사가 보고한 불일치를 관리자가 정정한다. {@code ADMIN} 역할만 호출할 수 있다(SecurityConfig).
 *
 * <p>정정은 돈을 직접 움직이므로 사유가 필수이고, 누가 했는지와 함께 로그와 원장 거래 설명에 남는다.
 */
@RestController
@RequestMapping("/api/v1/admin/ledger")
@RequiredArgsConstructor
public class AdminLedgerController {

    private final LedgerCorrectionService ledgerCorrectionService;

    /** 원장은 맞고 잔고·실현손익·보유 캐시가 틀렸을 때. 원장 합계로 다시 계산한다. */
    @PostMapping("/accounts/{accountId}/cache-rebuild")
    public LedgerCacheRebuildResult rebuildCaches(
        @PathVariable Long accountId,
        @Valid @RequestBody LedgerCorrectionRequest request,
        @AuthenticationPrincipal User admin
    ) {
        return ledgerCorrectionService.rebuildCaches(accountId, admin.getId(), request.reason());
    }

    /** 원장에 잘못된 거래가 기록됐을 때. 원거래는 그대로 두고 상쇄하는 거래를 추가한다. */
    @PostMapping("/transactions/{transactionId}/reversal")
    public LedgerReversalResult reverse(
        @PathVariable Long transactionId,
        @Valid @RequestBody LedgerCorrectionRequest request,
        @AuthenticationPrincipal User admin
    ) {
        return ledgerCorrectionService.reverse(transactionId, admin.getId(), request.reason());
    }
}
