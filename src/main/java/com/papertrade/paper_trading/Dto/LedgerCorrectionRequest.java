package com.papertrade.paper_trading.Dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 원장 정정 요청. 정정은 돈을 직접 움직이므로 사유가 필수다. */
public record LedgerCorrectionRequest(
    @NotBlank(message = "정정 사유가 필요합니다.")
    @Size(max = 150, message = "정정 사유는 150자 이하여야 합니다.")
    String reason
) {
}
