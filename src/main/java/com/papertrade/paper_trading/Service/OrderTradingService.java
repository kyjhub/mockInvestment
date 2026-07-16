package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Dto.OrderExecutionResponse;
import com.papertrade.paper_trading.Dto.OrderPlaceRequest;
import com.papertrade.paper_trading.Dto.OrderResponse;
import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.CashTransaction;
import com.papertrade.paper_trading.Entity.Execution;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.CashTransactionType;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.CashTransactionRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.StockRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderTradingService {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final HoldingRepository holdingRepository;
    private final CashTransactionRepository cashTransactionRepository;
    private final OrderBookService orderBookService;
    private final DailyPriceRangeService dailyPriceRangeService;

    @Transactional
    public OrderResponse placeOrder(User user, OrderPlaceRequest request) {
        if (user == null) {
            throw new IllegalArgumentException("인증 정보가 필요합니다.");
        }

        validateRequest(request);

        Account accountSnapshot = accountRepository.findByUserId(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));

        if (request.clientOrderId() != null && !request.clientOrderId().isBlank()) {
            Order existingOrder = orderRepository.findByAccountIdAndClientOrderId(
                accountSnapshot.getId(),
                request.clientOrderId()
            ).orElse(null);
            if (existingOrder != null) {
                return toResponse(existingOrder);
            }
        }

        Stock stock = stockRepository.findBySymbol(request.symbol())
            .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다."));

        OrderBookResponse orderBook = orderBookService.getOrderBook(stock.getSymbol());
        Account account = accountRepository.findByUserIdForUpdate(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));

        if (request.clientOrderId() != null && !request.clientOrderId().isBlank()) {
            Order existingOrder = orderRepository.findByAccountIdAndClientOrderId(
                account.getId(),
                request.clientOrderId()
            ).orElse(null);
            if (existingOrder != null) {
                return toResponse(existingOrder);
            }
        }

        DailyPriceRangeResponse dailyPriceRange = getDailyPriceRangeIfMarketOrder(request, stock);

        validateAssetAvailability(account, stock, request, orderBook, dailyPriceRange);

        Order order = Order.create(
            account,
            stock,
            normalizeClientOrderId(request.clientOrderId()),
            request.side(),
            request.orderType(),
            request.price(),
            request.quantity()
        );
        orderRepository.save(order);

        DailyPriceRangeResponse updatedDailyPriceRange;
        if (request.side() == OrderSide.BUY) {
            updatedDailyPriceRange = executeBuy(account, stock, order, orderBook, dailyPriceRange);
        } else {
            updatedDailyPriceRange = executeSell(account, stock, order, orderBook, dailyPriceRange);
        }

        applyMarketOrderRemainingPrice(order, updatedDailyPriceRange);

        return toResponse(order);
    }

    private DailyPriceRangeResponse executeBuy(
        Account account,
        Stock stock,
        Order order,
        OrderBookResponse orderBook,
        DailyPriceRangeResponse dailyPriceRange
    ) {
        Holding holding = holdingRepository.findByAccountIdAndStockId(account.getId(), stock.getId())
            .orElseGet(() -> holdingRepository.save(Holding.create(account, stock)));
        DailyPriceRangeResponse updatedDailyPriceRange = dailyPriceRange;

        for (OrderBookLevel ask : executableAskLevels(order, orderBook)) {
            if (order.getRemainingQuantity() == 0) {
                break;
            }

            Long executionQuantity = Math.min(
                order.getRemainingQuantity(),
                ask.volume()
            );
            if (executionQuantity <= 0) {
                break;
            }

            BigDecimal executionAmount = money(ask.price().multiply(BigDecimal.valueOf(executionQuantity)));
            if (account.getCashBalance().compareTo(executionAmount) < 0) {
                throw new IllegalArgumentException("주문 가능 금액이 부족합니다.");
            }

            Execution execution = createExecution(order, ask.price(), executionQuantity);
            account.debitCash(executionAmount);
            holding.buy(executionQuantity, ask.price());
            order.fill(executionQuantity);
            createCashTransaction(account, order, execution, CashTransactionType.BUY, executionAmount.negate());
            updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                stock.getSymbol(),
                updatedDailyPriceRange,
                ask.price()
            );
        }

        return updatedDailyPriceRange;
    }

    private DailyPriceRangeResponse executeSell(
        Account account,
        Stock stock,
        Order order,
        OrderBookResponse orderBook,
        DailyPriceRangeResponse dailyPriceRange
    ) {
        Holding holding = holdingRepository.findByAccountIdAndStockId(account.getId(), stock.getId())
            .orElseThrow(() -> new IllegalArgumentException("보유 수량이 부족합니다."));
        DailyPriceRangeResponse updatedDailyPriceRange = dailyPriceRange;

        if (holding.getQuantity() <= 0) {
            throw new IllegalArgumentException("보유 수량이 부족합니다.");
        }

        if (holding.getQuantity() < order.getOrderQuantity()) {
            throw new IllegalArgumentException("주문 수량이 보유 수량보다 많습니다.");
        }

        for (OrderBookLevel bid : executableBidLevels(order, orderBook)) {
            if (order.getRemainingQuantity() == 0 || holding.getQuantity() == 0) {
                break;
            }

            Long executionQuantity = Math.min(
                order.getRemainingQuantity(),
                Math.min(bid.volume(), holding.getQuantity())
            );
            BigDecimal executionAmount = money(bid.price().multiply(BigDecimal.valueOf(executionQuantity)));
            BigDecimal costBasis = holding.sell(executionQuantity);

            Execution execution = createExecution(order, bid.price(), executionQuantity);
            account.creditCash(executionAmount);
            account.addRealizedProfit(executionAmount.subtract(costBasis));
            order.fill(executionQuantity);
            createCashTransaction(account, order, execution, CashTransactionType.SELL, executionAmount);
            updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                stock.getSymbol(),
                updatedDailyPriceRange,
                bid.price()
            );
        }

        return updatedDailyPriceRange;
    }

    private void validateAssetAvailability(
        Account account,
        Stock stock,
        OrderPlaceRequest request,
        OrderBookResponse orderBook,
        DailyPriceRangeResponse dailyPriceRange
    ) {
        if (request.side() == OrderSide.BUY) {
            BigDecimal requiredCash = requiredCashForBuy(request, orderBook, dailyPriceRange);
            if (account.getCashBalance().compareTo(requiredCash) < 0) {
                throw new IllegalArgumentException("주문 가능 금액이 부족합니다.");
            }
            return;
        }

        Long holdingQuantity = holdingRepository.findByAccountIdAndStockId(account.getId(), stock.getId())
            .map(Holding::getQuantity)
            .orElse(0L);
        if (holdingQuantity < request.quantity()) {
            throw new IllegalArgumentException("주문 수량이 보유 수량보다 많습니다.");
        }
    }

    private BigDecimal requiredCashForBuy(
        OrderPlaceRequest request,
        OrderBookResponse orderBook,
        DailyPriceRangeResponse dailyPriceRange
    ) {
        if (request.orderType() == OrderType.LIMIT) {
            return money(request.price().multiply(BigDecimal.valueOf(request.quantity())));
        }

        long remainingQuantity = request.quantity();
        BigDecimal requiredCash = ZERO_MONEY;

        for (OrderBookLevel ask : askLevels(orderBook).stream()
            .sorted(Comparator.comparing(OrderBookLevel::price))
            .toList()) {
            if (remainingQuantity == 0) {
                break;
            }

            long executionQuantity = Math.min(remainingQuantity, ask.volume());
            requiredCash = requiredCash.add(money(ask.price().multiply(BigDecimal.valueOf(executionQuantity))));
            remainingQuantity -= executionQuantity;
        }

        BigDecimal dailyHighPrice = dailyHighPrice(dailyPriceRange);
        if (remainingQuantity > 0 && dailyHighPrice != null) {
            requiredCash = requiredCash.add(money(dailyHighPrice.multiply(BigDecimal.valueOf(remainingQuantity))));
        }

        return requiredCash;
    }

    private DailyPriceRangeResponse getDailyPriceRangeIfMarketOrder(OrderPlaceRequest request, Stock stock) {
        if (request.orderType() != OrderType.MARKET) {
            return null;
        }
        return dailyPriceRangeService.getDailyPriceRange(stock.getSymbol());
    }

    private void applyMarketOrderRemainingPrice(Order order, DailyPriceRangeResponse dailyPriceRange) {
        if (order.getOrderType() != OrderType.MARKET || order.getRemainingQuantity() == 0) {
            return;
        }

        BigDecimal waitingPrice = order.getOrderSide() == OrderSide.BUY
            ? dailyHighPrice(dailyPriceRange)
            : dailyLowPrice(dailyPriceRange);

        if (waitingPrice != null) {
            order.waitRemainingAt(waitingPrice);
        }
    }

    private BigDecimal dailyHighPrice(DailyPriceRangeResponse dailyPriceRange) {
        if (dailyPriceRange == null) {
            return null;
        }
        return dailyPriceRange.dailyHighPrice();
    }

    private BigDecimal dailyLowPrice(DailyPriceRangeResponse dailyPriceRange) {
        if (dailyPriceRange == null) {
            return null;
        }
        return dailyPriceRange.dailyLowPrice();
    }

    private List<OrderBookLevel> executableAskLevels(Order order, OrderBookResponse orderBook) {
        return askLevels(orderBook).stream()
            .filter(level -> order.getOrderType() == OrderType.MARKET || level.price().compareTo(order.getOrderPrice()) <= 0)
            .sorted(Comparator.comparing(OrderBookLevel::price))
            .toList();
    }

    private List<OrderBookLevel> executableBidLevels(Order order, OrderBookResponse orderBook) {
        return bidLevels(orderBook).stream()
            .filter(level -> order.getOrderType() == OrderType.MARKET || level.price().compareTo(order.getOrderPrice()) >= 0)
            .sorted(Comparator.comparing(OrderBookLevel::price).reversed())
            .toList();
    }

    private List<OrderBookLevel> askLevels(OrderBookResponse orderBook) {
        if (orderBook == null || orderBook.result() == null || orderBook.result().asks() == null) {
            return List.of();
        }
        return orderBook.result().asks();
    }

    private List<OrderBookLevel> bidLevels(OrderBookResponse orderBook) {
        if (orderBook == null || orderBook.result() == null || orderBook.result().bids() == null) {
            return List.of();
        }
        return orderBook.result().bids();
    }

    private Execution createExecution(Order order, BigDecimal executionPrice, Long executionQuantity) {
        return executionRepository.save(Execution.builder()
            .order(order)
            .executionPrice(executionPrice)
            .executionQuantity(executionQuantity)
            .commission(ZERO_MONEY)
            .tax(ZERO_MONEY)
            .build());
    }

    private void createCashTransaction(
        Account account,
        Order order,
        Execution execution,
        CashTransactionType transactionType,
        BigDecimal amount
    ) {
        cashTransactionRepository.save(CashTransaction.builder()
            .account(account)
            .order(order)
            .execution(execution)
            .transactionType(transactionType)
            .amount(money(amount))
            .balanceAfter(account.getCashBalance())
            .description(order.getStock().getSymbol() + " " + transactionType.name())
            .build());
    }

    private OrderResponse toResponse(Order order) {
        List<OrderExecutionResponse> executions = executionRepository.findByOrderIdOrderByIdAsc(order.getId())
            .stream()
            .map(execution -> new OrderExecutionResponse(
                execution.getId(),
                execution.getExecutionPrice(),
                execution.getExecutionQuantity(),
                execution.getCommission(),
                execution.getTax(),
                execution.getExecutedAt()
            ))
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
            order.getSubmittedAt(),
            order.getUpdatedAt(),
            executions
        );
    }

    private void validateRequest(OrderPlaceRequest request) {
        if (request.orderType() == OrderType.LIMIT && request.price() == null) {
            throw new IllegalArgumentException("지정가 주문에는 주문가격이 필요합니다.");
        }

        if (request.orderType() == OrderType.MARKET && request.price() != null) {
            throw new IllegalArgumentException("시장가 주문에는 주문가격을 입력할 수 없습니다.");
        }
    }

    private String normalizeClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return null;
        }
        return clientOrderId.trim();
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
