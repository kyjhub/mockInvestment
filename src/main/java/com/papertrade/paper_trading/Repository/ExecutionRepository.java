package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.Execution;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    List<Execution> findByOrderIdOrderByIdAsc(Long orderId);
}
