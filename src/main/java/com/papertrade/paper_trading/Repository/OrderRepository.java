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

    Optional<Order> findByAccountIdAndClientOrderId(Long accountId, String clientOrderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o from Order o
        where o.id <> :incomingOrderId
          and o.stock.id = :stockId
          and o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.SELL
          and o.status in :statuses
          and o.remainingQuantity > 0
          and o.orderPrice is not null
          and (:maxPrice is null or o.orderPrice <= :maxPrice)
        order by o.orderPrice asc, o.submittedAt asc, o.remainingQuantity desc
        """)
    List<Order> findMatchableSellOrders(
        @Param("incomingOrderId") Long incomingOrderId,
        @Param("stockId") Long stockId,
        @Param("maxPrice") BigDecimal maxPrice,
        @Param("statuses") Collection<OrderStatus> statuses,
        Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select o from Order o
        where o.id <> :incomingOrderId
          and o.stock.id = :stockId
          and o.orderSide = com.papertrade.paper_trading.Enum.OrderSide.BUY
          and o.status in :statuses
          and o.remainingQuantity > 0
          and o.orderPrice is not null
          and (:minPrice is null or o.orderPrice >= :minPrice)
        order by o.orderPrice desc, o.submittedAt asc, o.remainingQuantity desc
        """)
    List<Order> findMatchableBuyOrders(
        @Param("incomingOrderId") Long incomingOrderId,
        @Param("stockId") Long stockId,
        @Param("minPrice") BigDecimal minPrice,
        @Param("statuses") Collection<OrderStatus> statuses,
        Pageable pageable
    );

    @Query("select distinct o.stock.id from Order o where o.status in :statuses")
    List<Long> findDistinctStockIdsByStatusIn(@Param("statuses") Collection<OrderStatus> statuses);

    @Query("""
        select new com.papertrade.paper_trading.Repository.MatchableOrder(o.id, o.submittedAt)
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
