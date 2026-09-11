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
     */

    Optional<Order> findByAccountIdAndClientOrderId(Long accountId, String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o from Order o
        where o.id not in :excludedOrderIds
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
        @Param("stockId") Long stockId,
        @Param("maxPrice") BigDecimal maxPrice,
        @Param("statuses") Collection<OrderStatus> statuses,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o from Order o
        where o.id not in :excludedOrderIds
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

    record MatchableOrder(Long id, LocalDateTime submittedAt) {
    }

}
