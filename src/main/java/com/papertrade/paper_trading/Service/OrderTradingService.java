package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.OrderExecutionResponse;
import com.papertrade.paper_trading.Dto.OrderPlaceRequest;
import com.papertrade.paper_trading.Dto.OrderResponse;
import com.papertrade.paper_trading.Dto.SymbolMatchRequestedEvent;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Execution;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class OrderTradingService {

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final SymbolMatchRequestedStreamPublisher symbolMatchRequestedStreamPublisher;

    @Transactional
    public OrderResponse placeOrder(User user, OrderPlaceRequest request) {
        if (user == null) {
            throw new IllegalArgumentException("인증 정보가 필요합니다.");
        }

        validateRequest(request);

        Account account = accountRepository.findByUserId(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));

        if (request.clientOrderId() != null && !request.clientOrderId().isBlank()) {
            Order existingOrder = orderRepository.findByAccountIdAndClientOrderId(
                account.getId(),
                request.clientOrderId()
            ).orElse(null);
            if (existingOrder != null) {
                return toAcceptedResponse(existingOrder);
            }
        }

        Stock stock = stockRepository.findBySymbol(request.symbol())
            .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다."));

        Order order = Order.create(
            account,
            stock,
            normalizeClientOrderId(request.clientOrderId()),
            request.side(),
            request.orderType(),
            request.price(),
            request.quantity()
        );
        orderRepository.saveAndFlush(order);
        publishAfterCommit(stock.getSymbol());

        return toAcceptedResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrder(User user, Long orderId) {
        if (user == null) {
            throw new IllegalArgumentException("인증 정보가 필요합니다.");
        }

        Order order = orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        if (!order.getAccount().getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("주문을 취소할 권한이 없습니다.");
        }

        order.cancel();
        return toAcceptedResponse(order);
    }

    private void validateRequest(OrderPlaceRequest request) {
        if (request.orderType() == OrderType.LIMIT && request.price() == null) {
            throw new IllegalArgumentException("지정가 주문에는 주문가격이 필요합니다.");
        }

        if (request.orderType() == OrderType.MARKET && request.price() != null) {
            throw new IllegalArgumentException("시장가 주문에는 주문가격을 입력할 수 없습니다.");
        }
    }

    private void publishAfterCommit(String symbol) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                symbolMatchRequestedStreamPublisher.publish(
                    new SymbolMatchRequestedEvent(symbol, "ORDER_SUBMITTED")
                );
            }
        });
    }

    private String normalizeClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return null;
        }
        return clientOrderId.trim();
    }

    private OrderResponse toAcceptedResponse(Order order) {
        List<OrderExecutionResponse> executions = executionRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
            .map(this::toExecutionResponse)
            .toList();

        return new OrderResponse(
            order.getId(),
            order.getClientOrderId(),
            order.getStock().getSymbol(),
            order.getOrderSide(),
            order.getOrderType(),
            order.getOrderPrice(),
            order.getOrderQuantity(),
            order.getFilledQuantity(),
            order.getRemainingQuantity(),
            order.getStatus(),
            order.getRejectReason(),
            order.getSubmittedAt(),
            order.getUpdatedAt(),
            executions
        );
    }

    private OrderExecutionResponse toExecutionResponse(Execution execution) {
        return new OrderExecutionResponse(
            execution.getId(),
            execution.getExecutionPrice(),
            execution.getExecutionQuantity(),
            execution.getCommission(),
            execution.getTax(),
            execution.getExecutedAt()
        );
    }
}
