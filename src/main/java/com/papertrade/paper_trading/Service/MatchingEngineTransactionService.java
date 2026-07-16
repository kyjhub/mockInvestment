package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.CashTransaction;
import com.papertrade.paper_trading.Entity.Execution;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Entity.Stock;
import com.papertrade.paper_trading.Enum.CashTransactionType;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.CashTransactionRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MatchingEngineTransactionService {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    private static final List<OrderStatus> MATCHABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final HoldingRepository holdingRepository;
    private final CashTransactionRepository cashTransactionRepository;
    private final OrderBookService orderBookService;
    private final DailyPriceRangeService dailyPriceRangeService;

    @Transactional
    public void matchOrder(Long orderId) {
        Order incomingOrder = orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        if (!MATCHABLE_STATUSES.contains(incomingOrder.getStatus())) {
            return;
        }

        Stock stock = incomingOrder.getStock();
        DailyPriceRangeResponse dailyPriceRange = getDailyPriceRangeIfMarketOrder(incomingOrder);
        OrderBookResponse orderBook = orderBookService.getOrderBook(stock.getSymbol());

        if (incomingOrder.getOrderSide() == OrderSide.BUY) {
            matchBuyOrder(incomingOrder, orderBook, dailyPriceRange);
        } else {
            matchSellOrder(incomingOrder, orderBook, dailyPriceRange);
        }
    }

    private void matchBuyOrder(
        Order buyOrder,
        OrderBookResponse orderBook,
        DailyPriceRangeResponse dailyPriceRange
    ) {
        Account buyerAccount = accountRepository.findByIdForUpdate(buyOrder.getAccount().getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));
        Holding buyerHolding = holdingRepository.findByAccountIdAndStockId(
            buyerAccount.getId(),
            buyOrder.getStock().getId()
        ).orElseGet(() -> holdingRepository.save(Holding.create(buyerAccount, buyOrder.getStock())));

        DailyPriceRangeResponse updatedDailyPriceRange = dailyPriceRange;
        List<OrderBookLevel> externalAsks = executableAskLevels(buyOrder, orderBook);
        int externalAskIndex = 0;
        long consumedExternalAskQuantity = 0L;

        while (buyOrder.getRemainingQuantity() > 0) {
            Order internalSellOrder = bestInternalSellOrder(buyOrder);
            OrderBookLevel externalAsk = externalLevel(externalAsks, externalAskIndex);

            if (internalSellOrder == null && externalAsk == null) {
                break;
            }

            if (shouldUseInternalSell(internalSellOrder, externalAsk)) {
                BigDecimal executionPrice = internalSellOrder.getOrderPrice();
                Long executionQuantity = Math.min(buyOrder.getRemainingQuantity(), internalSellOrder.getRemainingQuantity());
                executeInternalTrade(buyOrder, internalSellOrder, executionPrice, executionQuantity);
                updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                    buyOrder.getStock().getSymbol(),
                    updatedDailyPriceRange,
                    executionPrice
                );
            } else {
                long availableExternalQuantity = externalAsk.volume() - consumedExternalAskQuantity;
                Long executionQuantity = Math.min(buyOrder.getRemainingQuantity(), availableExternalQuantity);
                executeExternalBuy(buyOrder, buyerAccount, buyerHolding, externalAsk.price(), executionQuantity);
                consumedExternalAskQuantity += executionQuantity;
                if (consumedExternalAskQuantity >= externalAsk.volume()) {
                    externalAskIndex++;
                    consumedExternalAskQuantity = 0L;
                }
                updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                    buyOrder.getStock().getSymbol(),
                    updatedDailyPriceRange,
                    externalAsk.price()
                );
            }
        }

        applyMarketOrderRemainingPrice(buyOrder, updatedDailyPriceRange);
    }

    private void matchSellOrder(
        Order sellOrder,
        OrderBookResponse orderBook,
        DailyPriceRangeResponse dailyPriceRange
    ) {
        Account sellerAccount = accountRepository.findByIdForUpdate(sellOrder.getAccount().getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));
        Holding sellerHolding = holdingRepository.findByAccountIdAndStockId(
            sellerAccount.getId(),
            sellOrder.getStock().getId()
        ).orElseThrow(() -> new IllegalArgumentException("보유 수량이 부족합니다."));

        DailyPriceRangeResponse updatedDailyPriceRange = dailyPriceRange;
        List<OrderBookLevel> externalBids = executableBidLevels(sellOrder, orderBook);
        int externalBidIndex = 0;
        long consumedExternalBidQuantity = 0L;

        while (sellOrder.getRemainingQuantity() > 0) {
            Order internalBuyOrder = bestInternalBuyOrder(sellOrder);
            OrderBookLevel externalBid = externalLevel(externalBids, externalBidIndex);

            if (internalBuyOrder == null && externalBid == null) {
                break;
            }

            if (shouldUseInternalBuy(internalBuyOrder, externalBid)) {
                BigDecimal executionPrice = internalBuyOrder.getOrderPrice();
                Long executionQuantity = Math.min(sellOrder.getRemainingQuantity(), internalBuyOrder.getRemainingQuantity());
                executeInternalTrade(internalBuyOrder, sellOrder, executionPrice, executionQuantity);
                updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                    sellOrder.getStock().getSymbol(),
                    updatedDailyPriceRange,
                    executionPrice
                );
            } else {
                long availableExternalQuantity = externalBid.volume() - consumedExternalBidQuantity;
                Long executionQuantity = Math.min(
                    sellOrder.getRemainingQuantity(),
                    Math.min(availableExternalQuantity, sellerHolding.getQuantity())
                );
                if (executionQuantity <= 0) {
                    break;
                }

                executeExternalSell(sellOrder, sellerAccount, sellerHolding, externalBid.price(), executionQuantity);
                consumedExternalBidQuantity += executionQuantity;
                if (consumedExternalBidQuantity >= externalBid.volume()) {
                    externalBidIndex++;
                    consumedExternalBidQuantity = 0L;
                }
                updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                    sellOrder.getStock().getSymbol(),
                    updatedDailyPriceRange,
                    externalBid.price()
                );
            }
        }

        applyMarketOrderRemainingPrice(sellOrder, updatedDailyPriceRange);
    }

    private void executeInternalTrade(Order buyOrder, Order sellOrder, BigDecimal executionPrice, Long executionQuantity) {
        Account buyerAccount = accountRepository.findByIdForUpdate(buyOrder.getAccount().getId())
            .orElseThrow(() -> new IllegalArgumentException("매수 계좌를 찾을 수 없습니다."));
        Account sellerAccount = accountRepository.findByIdForUpdate(sellOrder.getAccount().getId())
            .orElseThrow(() -> new IllegalArgumentException("매도 계좌를 찾을 수 없습니다."));

        Holding buyerHolding = holdingRepository.findByAccountIdAndStockId(
            buyerAccount.getId(),
            buyOrder.getStock().getId()
        ).orElseGet(() -> holdingRepository.save(Holding.create(buyerAccount, buyOrder.getStock())));
        Holding sellerHolding = holdingRepository.findByAccountIdAndStockId(
            sellerAccount.getId(),
            sellOrder.getStock().getId()
        ).orElseThrow(() -> new IllegalArgumentException("보유 수량이 부족합니다."));

        if (sellerHolding.getQuantity() < executionQuantity) {
            throw new IllegalArgumentException("보유 수량이 부족합니다.");
        }

        BigDecimal executionAmount = money(executionPrice.multiply(BigDecimal.valueOf(executionQuantity)));
        if (buyerAccount.getCashBalance().compareTo(executionAmount) < 0) {
            throw new IllegalArgumentException("주문 가능 금액이 부족합니다.");
        }

        BigDecimal costBasis = sellerHolding.sell(executionQuantity);
        buyerAccount.debitCash(executionAmount);
        sellerAccount.creditCash(executionAmount);
        sellerAccount.addRealizedProfit(executionAmount.subtract(costBasis));
        buyerHolding.buy(executionQuantity, executionPrice);
        buyOrder.fill(executionQuantity);
        sellOrder.fill(executionQuantity);

        Execution buyExecution = createExecution(buyOrder, executionPrice, executionQuantity);
        Execution sellExecution = createExecution(sellOrder, executionPrice, executionQuantity);
        createCashTransaction(buyerAccount, buyOrder, buyExecution, CashTransactionType.BUY, executionAmount.negate());
        createCashTransaction(sellerAccount, sellOrder, sellExecution, CashTransactionType.SELL, executionAmount);
    }

    private void executeExternalBuy(
        Order buyOrder,
        Account buyerAccount,
        Holding buyerHolding,
        BigDecimal executionPrice,
        Long executionQuantity
    ) {
        BigDecimal executionAmount = money(executionPrice.multiply(BigDecimal.valueOf(executionQuantity)));
        if (buyerAccount.getCashBalance().compareTo(executionAmount) < 0) {
            throw new IllegalArgumentException("주문 가능 금액이 부족합니다.");
        }

        Execution execution = createExecution(buyOrder, executionPrice, executionQuantity);
        buyerAccount.debitCash(executionAmount);
        buyerHolding.buy(executionQuantity, executionPrice);
        buyOrder.fill(executionQuantity);
        createCashTransaction(buyerAccount, buyOrder, execution, CashTransactionType.BUY, executionAmount.negate());
    }

    private void executeExternalSell(
        Order sellOrder,
        Account sellerAccount,
        Holding sellerHolding,
        BigDecimal executionPrice,
        Long executionQuantity
    ) {
        BigDecimal executionAmount = money(executionPrice.multiply(BigDecimal.valueOf(executionQuantity)));
        BigDecimal costBasis = sellerHolding.sell(executionQuantity);

        Execution execution = createExecution(sellOrder, executionPrice, executionQuantity);
        sellerAccount.creditCash(executionAmount);
        sellerAccount.addRealizedProfit(executionAmount.subtract(costBasis));
        sellOrder.fill(executionQuantity);
        createCashTransaction(sellerAccount, sellOrder, execution, CashTransactionType.SELL, executionAmount);
    }

    private Order bestInternalSellOrder(Order buyOrder) {
        return orderRepository.findMatchableSellOrders(
            buyOrder.getId(),
            buyOrder.getStock().getId(),
            limitPrice(buyOrder),
            MATCHABLE_STATUSES
        ).stream().findFirst().orElse(null);
    }

    private Order bestInternalBuyOrder(Order sellOrder) {
        return orderRepository.findMatchableBuyOrders(
            sellOrder.getId(),
            sellOrder.getStock().getId(),
            limitPrice(sellOrder),
            MATCHABLE_STATUSES
        ).stream().findFirst().orElse(null);
    }

    private List<OrderBookLevel> executableAskLevels(Order buyOrder, OrderBookResponse orderBook) {
        return askLevels(orderBook).stream()
            .filter(level -> limitPrice(buyOrder) == null || level.price().compareTo(limitPrice(buyOrder)) <= 0)
            .sorted(Comparator.comparing(OrderBookLevel::price))
            .toList();
    }

    private List<OrderBookLevel> executableBidLevels(Order sellOrder, OrderBookResponse orderBook) {
        return bidLevels(orderBook).stream()
            .filter(level -> limitPrice(sellOrder) == null || level.price().compareTo(limitPrice(sellOrder)) >= 0)
            .sorted(Comparator.comparing(OrderBookLevel::price).reversed())
            .toList();
    }

    private OrderBookLevel externalLevel(List<OrderBookLevel> levels, int index) {
        if (index >= levels.size()) {
            return null;
        }
        return levels.get(index);
    }

    private boolean shouldUseInternalSell(Order internalSellOrder, OrderBookLevel externalAsk) {
        if (internalSellOrder == null) {
            return false;
        }
        if (externalAsk == null) {
            return true;
        }
        return internalSellOrder.getOrderPrice().compareTo(externalAsk.price()) <= 0;
    }

    private boolean shouldUseInternalBuy(Order internalBuyOrder, OrderBookLevel externalBid) {
        if (internalBuyOrder == null) {
            return false;
        }
        if (externalBid == null) {
            return true;
        }
        return internalBuyOrder.getOrderPrice().compareTo(externalBid.price()) >= 0;
    }

    private BigDecimal limitPrice(Order order) {
        return order.getOrderPrice();
    }

    private DailyPriceRangeResponse getDailyPriceRangeIfMarketOrder(Order order) {
        if (order.getOrderType() != OrderType.MARKET) {
            return null;
        }
        return dailyPriceRangeService.getDailyPriceRange(order.getStock().getSymbol());
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

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
