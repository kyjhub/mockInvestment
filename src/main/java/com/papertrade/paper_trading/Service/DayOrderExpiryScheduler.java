package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Client.TossApiQuotaUnavailableException;
import com.papertrade.paper_trading.Config.SchedulingConfig;
import com.papertrade.paper_trading.Dto.MarketBusinessDay;
import com.papertrade.paper_trading.Dto.MarketCalendarResponse;
import com.papertrade.paper_trading.Dto.MarketCalendarResult;
import com.papertrade.paper_trading.Dto.MarketSession;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Repository.OrderRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 당일 유효(DAY) 주문을 거래일 종료 시점에 실효시킨다.
 *
 * <p>주문에 유효기간이 없으면 모든 주문이 사실상 GTC가 된다. 그러면 어제 낸 매수 주문의 구속액이
 * 계속 예수금을 깎고, 장이 닫혀 호가가 변하지 않는데도 재매칭 스케줄러가 그 종목을 계속 재발행한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DayOrderExpiryScheduler {

    private static final List<OrderStatus> EXPIRABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );
    private static final String EXPIRY_REASON = "거래일 종료로 실효되었습니다.";

    private final MarketCalendarService marketCalendarService;
    private final OrderRepository orderRepository;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(
        scheduler = SchedulingConfig.VALUATION_SCHEDULER,
        fixedDelayString = "${order.day-expiry.fixed-delay-ms:60000}"
    )
    public void expireDayOrders() {
        LocalDateTime closedAt = lastClosedTradingDayEnd();
        if (closedAt == null) {
            return;
        }

        List<Long> expirableOrderIds = orderRepository.findExpirableOrderIds(EXPIRABLE_STATUSES, closedAt);
        if (expirableOrderIds.isEmpty()) {
            return;
        }

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        int expired = 0;
        for (Long orderId : expirableOrderIds) {
            try {
                // 주문 하나가 하나의 트랜잭션이다. 매칭과 락이 겹쳐 실패해도 나머지가 막히지 않고,
                // batch가 멱등하므로 실패한 건은 다음 주기에 다시 대상이 된다.
                transactionTemplate.executeWithoutResult(ignored -> expireOne(orderId, closedAt));
                expired++;
            } catch (RuntimeException e) {
                log.warn("Failed to expire a day order. orderId={}, reason={}", orderId, e.getMessage());
            }
        }
        log.info("Expired day orders. closedAt={}, expired={}, attempted={}",
            closedAt, expired, expirableOrderIds.size());
    }

    private void expireOne(Long orderId, LocalDateTime closedAt) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        // 락을 잡는 사이 체결이 끝났거나 사용자가 취소했을 수 있다. 잠근 뒤 조건을 다시 본다.
        if (order == null
            || !EXPIRABLE_STATUSES.contains(order.getStatus())
            || !order.getSubmittedAt().isBefore(closedAt)) {
            return;
        }
        order.expire(EXPIRY_REASON);
    }

    /**
     * 이미 끝난 가장 최근 거래일의 종료 시각. 아직 끝난 거래일이 없으면 {@code null}.
     *
     * <p>기준은 정규장 마감이 아니라 <b>애프터마켓 종료</b>다. 토스는 한 거래일을
     * 주간거래 → 프리마켓 → 정규장 → 애프터마켓 네 세션으로 운영하므로, 정규장이 끝나도 두 시간 더
     * 거래가 가능하다. 정규장 마감에 실효시키면 아직 체결될 수 있는 주문을 죽이는 셈이다.
     *
     * <p>{@code today}와 {@code previousBusinessDay} 둘 다 보는 이유는, 호출 시점에 따라
     * {@code today}의 종료가 아직 미래일 수 있기 때문이다. 예를 들어 정규장이 도는 밤 시간에는
     * 오늘 거래일이 진행 중이므로 직전 거래일의 종료가 기준이 된다.
     *
     * <p>응답 시각은 offset을 가진 {@code OffsetDateTime}이라 서머타임과 조기 마감이 그대로 반영된다.
     * 우리가 마감 시각을 계산하지 않는 이유다.
     */
    private LocalDateTime lastClosedTradingDayEnd() {
        MarketCalendarResult calendar = marketCalendar();
        if (calendar == null) {
            return null;
        }

        OffsetDateTime now = OffsetDateTime.now();
        return Stream.of(calendar.today(), calendar.previousBusinessDay())
            .filter(Objects::nonNull)
            .map(MarketBusinessDay::afterMarket)
            .filter(Objects::nonNull)
            .map(MarketSession::endTime)
            .filter(Objects::nonNull)
            .filter(endTime -> !endTime.isAfter(now))
            .max(OffsetDateTime::compareTo)
            // submittedAt이 타임존 없는 LocalDateTime이라 서버 타임존 기준으로 맞춰야 비교가 성립한다.
            // toLocalDateTime()을 바로 부르면 응답 offset 기준 벽시계 시각이 나와 어긋난다.
            .map(endTime -> endTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime())
            .orElse(null);
    }

    private MarketCalendarResult marketCalendar() {
        try {
            MarketCalendarResponse response = marketCalendarService.getUsMarketCalendar(null);
            return response == null ? null : response.result();
        } catch (TossApiQuotaUnavailableException e) {
            // 만료는 늦어도 되는 작업이다. 다음 주기에 다시 시도한다.
            log.debug("Skipping day order expiry because the market calendar quota is exhausted.");
            return null;
        } catch (RuntimeException e) {
            // 달력은 외부 의존이라 인증 실패·타임아웃으로도 끊긴다. 마감 시각을 추측하는 것보다
            // 실효를 미루는 편이 안전하다. 모르는 채로 실효시키면 되돌릴 방법이 없다.
            log.warn("Skipping day order expiry because the market calendar call failed. reason={}",
                e.toString());
            return null;
        }
    }
}
