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
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class OrderTradingService {

    private static final int MONEY_SCALE = 2;
    private static final List<OrderStatus> RESERVING_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final HoldingRepository holdingRepository;
    private final DailyPriceRangeService dailyPriceRangeService;
    private final CommissionCalculator commissionCalculator;
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

        // 시세 조회는 계좌 row lock을 잡기 전에 끝낸다. 락을 쥔 채 Toss를 기다리면 그동안 그 계좌의 체결이 멈춘다.
        BigDecimal reservedUnitPrice = reservedUnitPrice(stock, request);

        // 락을 잡고 나서 구속액을 다시 집계한다. 잠그지 않으면 동시에 들어온 두 요청이
        // 같은 가용잔고를 읽고 둘 다 통과해 예수금을 넘긴다.
        Account lockedAccount = accountRepository.findByIdForUpdate(account.getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));
        if (request.side() == OrderSide.BUY) {
            validateOrderableAmount(lockedAccount, reservedUnitPrice, request.quantity());
        } else {
            validateSellableQuantity(lockedAccount, stock, request.quantity());
        }

        Order order = Order.create(
            lockedAccount,
            stock,
            normalizeClientOrderId(request.clientOrderId()),
            request.side(),
            request.orderType(),
            request.price(),
            request.quantity(),
            reservedUnitPrice
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

    /**
     * 이 주문이 1주당 구속할 금액. 매도 주문은 현금을 구속하지 않으므로 {@code null}이다.
     *
     * <p>시장가 매수는 주문가격이 없어 접수 시점에 주문금액을 계산할 수 없다. 당일 고가를 기준으로 삼는데,
     * 이는 {@code MatchingEngineTransactionService.applyMarketOrderRemainingPrice()}가 시장가 잔여
     * 물량의 대기 가격으로 심는 값과 같다. 덕분에 구속 기준이 주문 생애 내내 한 가지로 이어진다.
     *
     * <p>당일 고가는 "지금까지 거래된 최고가"지 "오늘 도달 가능한 최고가"가 아니다. 급등 구간에서는
     * 구속이 실제 체결금액보다 작을 수 있는데, 그때는 체결 시점의 잔고 캡이 방어선이 된다 —
     * 살 수 있는 만큼만 체결되고 잔량은 거절된다.
     */
    private BigDecimal reservedUnitPrice(Stock stock, OrderPlaceRequest request) {
        if (request.side() != OrderSide.BUY) {
            return null;
        }
        if (request.orderType() == OrderType.LIMIT) {
            return request.price();
        }

        BigDecimal dailyHighPrice = dailyPriceRangeService.getDailyPriceRange(stock.getSymbol()).dailyHighPrice();
        if (dailyHighPrice == null || dailyHighPrice.signum() <= 0) {
            // 구속 금액을 계산할 수 없는 주문을 받아들이면 예수금 규칙에 구멍이 생긴다.
            throw new IllegalArgumentException("시장가 주문을 받을 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        return dailyHighPrice;
    }

    private void validateOrderableAmount(Account account, BigDecimal reservedUnitPrice, Long quantity) {
        BigDecimal required = money(reservedUnitPrice.multiply(BigDecimal.valueOf(quantity)))
            .add(money(commissionCalculator.calculateCommission(reservedUnitPrice, quantity)))
            .add(money(commissionCalculator.calculateTax(reservedUnitPrice, quantity)));

        BigDecimal orderableAmount = account.getCashBalance()
            .subtract(orderRepository.sumReservedCash(account.getId(), RESERVING_STATUSES));

        if (orderableAmount.compareTo(required) < 0) {
            throw new IllegalArgumentException("주문가능금액이 부족합니다.");
        }
    }

    private void validateSellableQuantity(Account account, Stock stock, Long quantity) {
        long heldQuantity = holdingRepository.findByAccountIdAndStockId(account.getId(), stock.getId())
            .map(Holding::getQuantity)
            .orElse(0L);
        long reservedQuantity = orderRepository.sumReservedQuantity(
            account.getId(),
            stock.getId(),
            RESERVING_STATUSES
        );

        if (heldQuantity - reservedQuantity < quantity) {
            throw new IllegalArgumentException("매도가능수량이 부족합니다.");
        }
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
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
