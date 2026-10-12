package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.OrderRepository.CashReservation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 미체결 매수 주문이 예수금에서 묶어 두는 금액. {@code 주문가능금액 = 예수금 − 구속액}이다.
 *
 * <p>구속액은 저장하지 않고 미체결 주문에서 매번 파생한다. 컬럼으로 두면 체결·취소·거절마다 해제 코드가
 * 필요하고, 하나라도 빠지면 그 금액이 영구히 묶인다. 주문에서 파생하면 해제 경로라는 것이 존재하지 않는다.
 *
 * <p>주문 한 건의 구속액은 <b>남은 수량의 체결대금 + 앞으로 더 매길 수수료·세금</b>이다. 체결대금만 묶으면
 * 이미 걸린 주문들의 수수료만큼 예수금을 넘는 주문이 접수된다. 수수료는 체결 경로와 같은 규칙
 * ({@link TradingFees}, 주문 누적 체결금액 기준)으로 계산하므로, 지정가 이하로만 체결되는 한 실제 차감액이
 * 구속액을 넘지 않는다. 그래도 접수와 체결 사이에 요율이 바뀔 수 있으므로 체결 시점 잔고 캡이 최종
 * 방어선으로 남는다.
 */
@Component
@RequiredArgsConstructor
public class CashReservationCalculator {

    /**
     * 예수금·보유 수량을 묶어 두는 주문 상태. 접수 검증 대기 주문도 들어간다 — 지정가 매수는 접수할 때 이미
     * 구속액 검증을 통과했고, 매도는 수량을 묶어야 같은 주식을 두 번 팔지 못한다.
     * 가격이 아직 없는 시장가 매수는 구속 단가가 {@code null}이라 합계에 들어가지 않는다.
     */
    public static final List<OrderStatus> RESERVING_STATUSES = List.of(
        OrderStatus.AWAITING_PRICE,
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private static final int MONEY_SCALE = 2;

    private final OrderRepository orderRepository;
    private final TradingFees tradingFees;

    /** 이 계좌의 미체결 매수 주문 전체가 묶어 두는 금액. */
    public BigDecimal reservedCash(Long accountId) {
        return orderRepository.findCashReservations(accountId, RESERVING_STATUSES).stream()
            .map(this::reservedCash)
            .reduce(money(BigDecimal.ZERO), BigDecimal::add);
    }

    /** 새 주문이 이 단가로 이 수량을 사는 데 묶어야 하는 금액. */
    public BigDecimal requiredCash(BigDecimal unitPrice, long quantity) {
        BigDecimal tradeAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        return money(tradeAmount).add(tradingFees.forAmount(tradeAmount).total());
    }

    /**
     * 부분 체결된 주문은 남은 수량만 묶는다. 수수료는 "다 체결됐을 때의 누적 수수료 − 이미 부과한 수수료"다.
     * 남은 수량의 수수료를 따로 반올림하면 주문 전체 수수료와 센트 단위로 어긋난다.
     */
    private BigDecimal reservedCash(CashReservation reservation) {
        BigDecimal remainingAmount = reservation.unitPrice().multiply(BigDecimal.valueOf(reservation.remainingQuantity()));
        return money(remainingAmount).add(tradingFees.outstanding(
            reservation.filledAmount().add(remainingAmount),
            reservation.chargedCommission(),
            reservation.chargedTax()
        ).total());
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
