package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Enum.OrderStatus;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /*
     * 매칭 후보 조회는 자기 주문(incoming)뿐 아니라 "이번 스윕에서 체결 불가로 판명된 상대"도 제외한다.
     * 제외하지 않으면 같은 후보를 계속 다시 뽑아 무한 루프가 된다.
     * 호출자가 항상 자기 주문 id를 넣고 시작하므로 컬렉션이 비는 경우는 없다.
     *
     * 같은 계좌도 제외한다. 자기 자신과 체결하면 현금은 나갔다 들어와 순변동이 0인데
     * 실현손익은 그대로 적립되고 평균단가도 바뀐다. 가격을 스스로 정할 수 있으므로
     * 원하는 만큼 손익을 만들어낼 수 있는 경로가 된다.
     */

    Optional<Order> findByAccountIdAndClientOrderId(Long accountId, String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o from Order o
        where o.id not in :excludedOrderIds
          and o.account.id <> :accountId
          and o.stock.id = :stockId
          and o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.SELL
          and o.status in :statuses
          and o.remainingQuantity > 0
          and o.orderPrice is not null
          and (:maxPrice is null or o.orderPrice <= :maxPrice)
        order by o.orderPrice asc, o.submittedAt asc, o.remainingQuantity desc
        """)
    List<Order> findMatchableSellOrders(
        @Param("excludedOrderIds") Collection<Long> excludedOrderIds,
        @Param("accountId") Long accountId,
        @Param("stockId") Long stockId,
        @Param("maxPrice") BigDecimal maxPrice,
        @Param("statuses") Collection<OrderStatus> statuses,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o from Order o
        where o.id not in :excludedOrderIds
          and o.account.id <> :accountId
          and o.stock.id = :stockId
          and o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.BUY
          and o.status in :statuses
          and o.remainingQuantity > 0
          and o.orderPrice is not null
          and (:minPrice is null or o.orderPrice >= :minPrice)
        order by o.orderPrice desc, o.submittedAt asc, o.remainingQuantity desc
        """)
    List<Order> findMatchableBuyOrders(
        @Param("excludedOrderIds") Collection<Long> excludedOrderIds,
        @Param("accountId") Long accountId,
        @Param("stockId") Long stockId,
        @Param("minPrice") BigDecimal minPrice,
        @Param("statuses") Collection<OrderStatus> statuses,
        Pageable pageable
    );

    /**
     * 미체결 주문이 있는 종목을 <b>가장 먼저 접수된 주문 시각 순</b>으로 반환한다.
     *
     * <p>이 순서가 WebSocket 실시간 호가 정원(연결당 100 × 슬롯 수)을 누가 차지할지 정한다.
     * 정원을 넘긴 종목은 REST 폴링으로 밀려 갱신이 크게 느려지므로, 먼저 낸 주문이 먼저 기회를 받도록 한다.
     * 종목 <i>안에서의</i> 체결 순서는 {@link #findMatchableOrdersBySymbol}의 가격-시간 우선순위가 정한다.
     */
    @Query("""
        select o.stock.id
        from Order o
        where o.status in :statuses
        group by o.stock.id
        order by min(o.submittedAt) asc
        """)
    List<Long> findStockIdsByStatusInOrderByEarliestSubmittedAt(
        @Param("statuses") Collection<OrderStatus> statuses
    );

    @Query("""
        select new com.papertrade.paper_trading.Repository.OrderRepository$MatchableOrder(o.id, o.submittedAt)
        from Order o
        where o.stock.symbol = :symbol
          and o.status in :statuses
          and o.remainingQuantity > 0
        order by
          case when o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.BUY then 0 else 1 end,
          case when o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.BUY then o.orderPrice end desc,
          case when o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.SELL then o.orderPrice end asc,
          o.submittedAt asc,
          o.remainingQuantity desc
        """)
    List<MatchableOrder> findMatchableOrdersBySymbol(
        @Param("symbol") String symbol,
        @Param("statuses") Collection<OrderStatus> statuses
    );

    /**
     * 미체결 매수 주문의 구속 단가와 잔량. 구속액 계산은 {@code CashReservationCalculator}가 한다.
     *
     * <p>SQL로 {@code sum(reserved_unit_price * remaining_quantity)}를 바로 내지 않는 이유는 수수료다.
     * 수수료 체계(정률, 최소 수수료, 구간제)는 SQL로 표현할 수 없어서, 주문별로 읽어 계산기로 더한다.
     *
     * <p>구속액을 컬럼으로 저장하지 않고 여기서 파생하므로 해제 경로라는 것이 존재하지 않는다 — 체결되면
     * remainingQuantity가 줄고 취소·거절·실효되면 status가 빠지면서 합계에서 자동으로 사라진다.
     */
    @Query("""
        select new com.papertrade.paper_trading.Repository.OrderRepository$CashReservation(
            o.reservedUnitPrice, o.remainingQuantity)
        from Order o
        where o.account.id = :accountId
          and o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.BUY
          and o.status in :statuses
          and o.reservedUnitPrice is not null
          and o.remainingQuantity > 0
        """)
    List<CashReservation> findCashReservations(
        @Param("accountId") Long accountId,
        @Param("statuses") Collection<OrderStatus> statuses
    );

    /** 미체결 매도 주문이 묶어 둔 수량. 종목 단위다. */
    @Query("""
        select coalesce(sum(o.remainingQuantity), 0)
        from Order o
        where o.account.id = :accountId
          and o.stock.id = :stockId
          and o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.SELL
          and o.status in :statuses
        """)
    long sumReservedQuantity(
        @Param("accountId") Long accountId,
        @Param("stockId") Long stockId,
        @Param("statuses") Collection<OrderStatus> statuses
    );

    /**
     * 거래일 종료로 실효시킬 주문. <b>종료 시각 이전에 접수된</b> 미체결 주문만이다.
     *
     * <p>접수 시각 조건이 핵심이다. 종료 이후 접수된 주문은 다음 거래일 주문(예약주문)이므로
     * 이번 만료 대상이 아니다. 이 조건 덕분에 batch가 멱등해져 주기적으로 돌려도 안전하다.
     *
     * <p>id 순으로 돌려준다. 주문 row를 하나씩 잠그며 처리하므로 획득 순서가 고정돼야 한다.
     */
    @Query("""
        select o.id
        from Order o
        where o.status in :statuses
          and o.submittedAt < :closedAt
        order by o.id asc
        """)
    List<Long> findExpirableOrderIds(
        @Param("statuses") Collection<OrderStatus> statuses,
        @Param("closedAt") LocalDateTime closedAt
    );

    /**
     * 접수 검증 대기 주문과 그 종목. 현재가를 종목 단위로 한 번에 조회하려고 종목 코드를 함께 돌려준다.
     *
     * <p>id 순으로 돌려준다. 주문 row를 하나씩 잠그며 처리하므로 획득 순서가 고정돼야 한다.
     */
    @Query("""
        select new com.papertrade.paper_trading.Repository.OrderRepository$AwaitingPriceOrder(o.id, o.stock.symbol)
        from Order o
        where o.status = com.papertrade.paper_trading.Enum.OrderStatus.AWAITING_PRICE
        order by o.id asc
        """)
    List<AwaitingPriceOrder> findAwaitingPriceOrders();

    record MatchableOrder(Long id, LocalDateTime submittedAt) {
    }

    record AwaitingPriceOrder(Long id, String symbol) {
    }

    record CashReservation(BigDecimal unitPrice, Long quantity) {
    }

}
