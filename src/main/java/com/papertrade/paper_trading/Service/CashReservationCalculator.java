package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Repository.OrderRepository;
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
 * <p>주문 한 건의 구속액은 <b>체결대금에 수수료·세금 예상액을 더한 값</b>이다. 체결대금만 묶으면 이미 걸린
 * 주문들의 수수료만큼 예수금을 넘는 주문이 접수된다. 수수료 체계는 계산기마다 달라(정률, 최소 수수료,
 * 구간제) SQL로 합산할 수 없으므로, 주문을 읽어 계산기로 더한다. 계좌당 미체결 주문은 당일 실효 덕분에
 * 하루치로 제한된다.
 *
 * <p>접수 시점의 예상액이라 체결 시점 실제 차감액과 다를 수 있다 — 부분 체결마다 최소 수수료가 붙는 경우가
 * 그렇다. 그래서 체결 시점의 잔고 캡({@code MatchingEngineTransactionService.affordableQuantity})이
 * 최종 방어선으로 남는다.
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
    private final CommissionCalculator commissionCalculator;

    /** 이 계좌의 미체결 매수 주문 전체가 묶어 두는 금액. */
    public BigDecimal reservedCash(Long accountId) {
        return orderRepository.findCashReservations(accountId, RESERVING_STATUSES).stream()
            .map(reservation -> requiredCash(reservation.unitPrice(), reservation.quantity()))
            .reduce(money(BigDecimal.ZERO), BigDecimal::add);
    }

    /**
     * 이 단가로 이 수량을 사는 데 묶어야 하는 금액. 체결 경로와 같은 반올림으로 체결대금·수수료·세금을 더한다.
     */
    public BigDecimal requiredCash(BigDecimal unitPrice, long quantity) {
        return money(unitPrice.multiply(BigDecimal.valueOf(quantity)))
            .add(money(commissionCalculator.calculateCommission(unitPrice, quantity)))
            .add(money(commissionCalculator.calculateTax(unitPrice, quantity)));
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
