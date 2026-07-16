package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByAccountIdAndClientOrderId(Long accountId, String clientOrderId);
}
