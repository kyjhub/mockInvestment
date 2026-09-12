package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.DailyPriceRangeResponse;
import com.papertrade.paper_trading.Dto.OrderBookLevel;
import com.papertrade.paper_trading.Dto.OrderBookResponse;
import com.papertrade.paper_trading.Entity.Account;
import com.papertrade.paper_trading.Entity.Execution;
import com.papertrade.paper_trading.Entity.Holding;
import com.papertrade.paper_trading.Entity.LedgerTransaction;
import com.papertrade.paper_trading.Entity.Order;
import com.papertrade.paper_trading.Enum.LedgerAccount;
import com.papertrade.paper_trading.Enum.LedgerTransactionType;
import com.papertrade.paper_trading.Enum.OrderSide;
import com.papertrade.paper_trading.Enum.OrderStatus;
import com.papertrade.paper_trading.Enum.OrderType;
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.OrderRepository.MatchableOrder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.papertrade.paper_trading.Service.LedgerPostingService.Posting;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingEngineTransactionService {

    private static final int MONEY_SCALE = 2;
    private static final Pageable FIRST_MATCHABLE_ORDER = PageRequest.of(0, 1);
    /**
     * 상대 계좌를 고르기 위해 훑어볼 주문 수.
     *
     * <p>가격-시간 우선순위 순으로 읽어 앞에서부터 계좌를 모은다. 한 계좌가 여러 주문을 낼 수 있어
     * 주문 수는 계좌 수보다 넉넉해야 한다.
     */
    private static final Pageable COUNTERPARTY_SCAN_LIMIT = PageRequest.of(0, 100);

    /**
     * 한 매칭이 미리 잠글 상대 계좌 수 상한.
     *
     * <p>많이 잡을수록 더 많은 주문을 한 번에 체결할 수 있지만 그만큼 다른 매칭을 막는다.
     * 잠긴 계좌는 체결 상대가 아니어도 그동안 주문을 낼 수 없다.
     *
     * <p>상한을 넘는 상대가 필요한 큰 주문도 이 스윕 안에서 다 채워진다. {@code matchSymbol()}이
     * 그 종목의 미체결 주문을 <b>전부</b> 순회하므로, 상한에 밀린 상대들이 각자 자기 차례에 이 주문을
     * 상대로 체결하기 때문이다. 상한은 "한 트랜잭션이 잠그는 범위"를 정할 뿐 체결 범위를 정하지 않는다.
     */
    private static final int MAX_COUNTERPARTY_ACCOUNTS = 10;

    private static final List<OrderStatus> MATCHABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final HoldingRepository holdingRepository;
    private final LedgerPostingService ledgerPostingService;
    private final DailyPriceRangeService dailyPriceRangeService;
    private final OrderBookService orderBookService;
    private final CommissionCalculator commissionCalculator;
    private final PlatformTransactionManager transactionManager;

    public void matchSymbol(String symbol, DailyPriceRangeResponse dailyPriceRange) {
        List<MatchableOrder> matchableOrders = orderRepository.findMatchableOrdersBySymbol(symbol, MATCHABLE_STATUSES);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        for (MatchableOrder matchableOrder : matchableOrders) {
            // Toss 호출은 주문의 pessimistic lock을 잡기 전, transaction 밖에서 수행한다.
            // WebSocket 구독 종목이면 호출 자체가 없다 — 캐시가 곧 최신 상태다.
            OrderBookResponse orderBook = orderBookService.getOrderBookForMatching(symbol, matchableOrder.submittedAt());
            Long orderId = matchableOrder.id();
            try {
                transactionTemplate.executeWithoutResult(ignored -> matchOrder(orderId, orderBook, dailyPriceRange));
            } catch (IllegalArgumentException e) {
                // 체결 수량에 잔고·보유 캡을 미리 씌우므로, 여기까지 오는 예외는 정상적인 "조건 미달"이 아니라
                // 데이터 불일치 신호다. 그래도 루프는 중단하지 않는다 — 중단하면 뒤에 줄 선 정상 주문까지
                // 영영 처리되지 않고, 재시도해도 같은 주문이 같은 자리에서 다시 막는다.
                // DB·외부 API 장애 같은 시스템 문제는 잡지 않고 전파해서 재시도와 DLQ 경로를 그대로 타게 한다.
                log.error(
                    "Skipping order that cannot be matched. symbol={}, orderId={}, reason={}",
                    symbol,
                    orderId,
                    e.getMessage(),
                    e
                );
            }
        }
    }

    @Transactional
    public void matchOrder(Long orderId, OrderBookResponse orderBook, DailyPriceRangeResponse dailyPriceRange) {
        Order incomingOrder = orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        if (!MATCHABLE_STATUSES.contains(incomingOrder.getStatus())) {
            return;
        }

        Map<Long, Account> lockedAccounts = lockParticipantAccounts(incomingOrder);

        if (incomingOrder.getOrderSide() == OrderSide.BUY) {
            matchBuyOrder(incomingOrder, orderBook, dailyPriceRange, lockedAccounts);
        } else {
            matchSellOrder(incomingOrder, orderBook, dailyPriceRange, lockedAccounts);
        }
    }

    /**
     * 이 매칭이 건드릴 수 있는 계좌를 <b>id 오름차순으로</b> 모두 잠근다.
     *
     * <p>예전에는 진입 시점에 자기 계좌를 잡고, 내부 체결 때 상대 계좌를 추가로 잡았다. 그러면
     * 종목 A의 매칭이 계좌1→계좌2 순으로, 종목 B의 매칭이 계좌2→계좌1 순으로 잡을 수 있다.
     * 심볼 락은 종목 단위라 두 매칭이 동시에 돌 수 있으므로 데드락이 성립했다.
     *
     * <p>모든 트랜잭션이 같은 순서로 잡으면 순환이 만들어지지 않는다. 그래서 상대 후보 계좌를
     * <b>락 없이</b> 먼저 읽고, 자기 계좌까지 합쳐 id 순으로 하나씩 잠근다.
     *
     * <p>여기서 잠기지 않은 계좌의 주문은 이번 매칭에서 상대로 쓰지 않는다. 락 없이 읽은 뒤
     * 상태가 바뀌었거나 후보 수 상한을 넘긴 경우인데, 그 주문은 다음 매칭에서 다시 후보가 된다.
     * 순서를 지키려고 나중에 낮은 id를 잡는 것보다 한 번 거르는 쪽이 안전하다.
     */
    private Map<Long, Account> lockParticipantAccounts(Order incomingOrder) {
        Long incomingAccountId = incomingOrder.getAccount().getId();
        List<Long> scannedAccountIds = incomingOrder.getOrderSide() == OrderSide.BUY
            ? orderRepository.findMatchableSellAccountIds(
                incomingAccountId, incomingOrder.getStock().getId(), limitPrice(incomingOrder),
                MATCHABLE_STATUSES, COUNTERPARTY_SCAN_LIMIT)
            : orderRepository.findMatchableBuyAccountIds(
                incomingAccountId, incomingOrder.getStock().getId(), limitPrice(incomingOrder),
                MATCHABLE_STATUSES, COUNTERPARTY_SCAN_LIMIT);

        // 우선순위를 유지한 채 중복을 제거하고 상한까지 자른다. 상한에 걸려 잘려나가는 것은
        // 가격이 나쁜 쪽이어야 한다 — 그래서 조회를 가격-시간 순으로 받는다.
        Set<Long> counterpartyAccountIds = new LinkedHashSet<>(scannedAccountIds);
        SortedSet<Long> orderedAccountIds = counterpartyAccountIds.stream()
            .limit(MAX_COUNTERPARTY_ACCOUNTS)
            .collect(Collectors.toCollection(TreeSet::new));
        orderedAccountIds.add(incomingAccountId);

        Map<Long, Account> lockedAccounts = new HashMap<>();
        for (Long accountId : orderedAccountIds) {
            accountRepository.findByIdForUpdate(accountId)
                .ifPresent(account -> lockedAccounts.put(accountId, account));
        }

        if (!lockedAccounts.containsKey(incomingAccountId)) {
            throw new IllegalArgumentException("계좌를 찾을 수 없습니다.");
        }
        return lockedAccounts;
    }

    private void matchBuyOrder(
        Order buyOrder,
        OrderBookResponse orderBook,
        DailyPriceRangeResponse dailyPriceRange,
        Map<Long, Account> lockedAccounts
    ) {
        Account buyerAccount = lockedAccounts.get(buyOrder.getAccount().getId());
        Holding buyerHolding = holdingRepository.findByAccountIdAndStockId(
            buyerAccount.getId(),
            buyOrder.getStock().getId()
        ).orElseGet(() -> holdingRepository.save(Holding.create(buyerAccount, buyOrder.getStock())));

        DailyPriceRangeResponse updatedDailyPriceRange = dailyPriceRange;
        List<OrderBookLevel> externalAsks = executableAskLevels(buyOrder, orderBook);
        int externalAskIndex = 0;
        long consumedExternalAskQuantity = 0L;
        Set<Long> excludedSellOrderIds = new HashSet<>();
        excludedSellOrderIds.add(buyOrder.getId());

        while (buyOrder.getRemainingQuantity() > 0) {
            Order internalSellOrder = bestInternalSellOrder(buyOrder, excludedSellOrderIds, lockedAccounts.keySet());
            OrderBookLevel externalAsk = externalLevel(externalAsks, externalAskIndex);

            if (internalSellOrder == null && externalAsk == null) {
                // 체결 상대가 아예 없다. 계좌 문제가 아니라 유동성 문제이므로 거절하지 않고 대기시킨다.
                break;
            }

            if (shouldUseInternalSell(internalSellOrder, externalAsk)) {
                BigDecimal executionPrice = internalSellOrder.getOrderPrice();
                long affordableQuantity = affordableQuantity(buyerAccount, executionPrice);
                if (affordableQuantity <= 0) {
                    // 가장 싼 후보를 1주도 못 산다. 이후 후보는 가격이 비감소라 마찬가지다.
                    // 체결 상대는 눈앞에 있는데 잔고가 모자란 것이므로 거절로 종료한다.
                    buyOrder.reject("주문 가능 금액이 부족합니다.");
                    return;
                }

                long requestedQuantity = Math.min(
                    Math.min(buyOrder.getRemainingQuantity(), internalSellOrder.getRemainingQuantity()),
                    affordableQuantity
                );
                long executedQuantity = executeInternalTrade(buyOrder, internalSellOrder, executionPrice, requestedQuantity, lockedAccounts);
                if (executedQuantity <= 0) {
                    // 상대 매도자의 보유가 비어 있다. 같은 후보를 다시 뽑으면 무한 루프이므로 제외하고 계속한다.
                    excludedSellOrderIds.add(internalSellOrder.getId());
                    continue;
                }

                updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                    buyOrder.getStock().getSymbol(),
                    updatedDailyPriceRange,
                    executionPrice
                );
            } else {
                long affordableQuantity = affordableQuantity(buyerAccount, externalAsk.price());
                if (affordableQuantity <= 0) {
                    buyOrder.reject("주문 가능 금액이 부족합니다.");
                    return;
                }

                long availableExternalQuantity = externalAsk.volume() - consumedExternalAskQuantity;
                long executionQuantity = Math.min(
                    Math.min(buyOrder.getRemainingQuantity(), availableExternalQuantity),
                    affordableQuantity
                );
                if (executionQuantity <= 0) {
                    break;
                }

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
        DailyPriceRangeResponse dailyPriceRange,
        Map<Long, Account> lockedAccounts
    ) {
        Account sellerAccount = lockedAccounts.get(sellOrder.getAccount().getId());
        Holding sellerHolding = holdingRepository.findByAccountIdAndStockId(
            sellerAccount.getId(),
            sellOrder.getStock().getId()
        ).orElse(null);

        if (sellerHolding == null || sellerHolding.getQuantity() <= 0) {
            sellOrder.reject("보유 수량이 부족합니다.");
            return;
        }

        DailyPriceRangeResponse updatedDailyPriceRange = dailyPriceRange;
        List<OrderBookLevel> externalBids = executableBidLevels(sellOrder, orderBook);
        int externalBidIndex = 0;
        long consumedExternalBidQuantity = 0L;
        Set<Long> excludedBuyOrderIds = new HashSet<>();
        excludedBuyOrderIds.add(sellOrder.getId());

        while (sellOrder.getRemainingQuantity() > 0) {
            Order internalBuyOrder = bestInternalBuyOrder(sellOrder, excludedBuyOrderIds, lockedAccounts.keySet());
            OrderBookLevel externalBid = externalLevel(externalBids, externalBidIndex);

            if (internalBuyOrder == null && externalBid == null) {
                // 유동성 문제다. 거절하지 않는다.
                break;
            }

            // 체결 상대가 있는지 먼저 보고 나서 보유를 본다. 유동성이 없어서 못 판 것과
            // 팔 물량이 없어서 못 판 것은 다른 사유고, 전자는 거절 대상이 아니다.
            if (sellerHolding.getQuantity() <= 0) {
                sellOrder.reject("보유 수량이 부족합니다.");
                return;
            }

            if (shouldUseInternalBuy(internalBuyOrder, externalBid)) {
                BigDecimal executionPrice = internalBuyOrder.getOrderPrice();
                long requestedQuantity = Math.min(
                    Math.min(sellOrder.getRemainingQuantity(), internalBuyOrder.getRemainingQuantity()),
                    sellerHolding.getQuantity()
                );
                long executedQuantity = executeInternalTrade(internalBuyOrder, sellOrder, executionPrice, requestedQuantity, lockedAccounts);
                if (executedQuantity <= 0) {
                    // 상대 매수자의 잔고가 부족하다. 제외하고 다음 후보로.
                    excludedBuyOrderIds.add(internalBuyOrder.getId());
                    continue;
                }

                updatedDailyPriceRange = dailyPriceRangeService.updateWithExecutionPrice(
                    sellOrder.getStock().getSymbol(),
                    updatedDailyPriceRange,
                    executionPrice
                );
            } else {
                long availableExternalQuantity = externalBid.volume() - consumedExternalBidQuantity;
                long executionQuantity = Math.min(
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

    /**
     * 요청 수량에 매수자 잔고와 매도자 보유 수량으로 캡을 씌운 뒤 체결하고, <b>실제 체결한 수량</b>을 반환한다.
     *
     * <p>제약 위반을 예외로 알리지 않는 것이 핵심이다. 예외를 던지면 같은 트랜잭션에서 이미 성사된 체결까지
     * 롤백되고, 재시도해도 같은 자리에서 또 막히므로 체결 가능한 물량마저 영영 체결되지 않는다.
     *
     * <p>0을 반환하면 "이 상대방과는 체결할 수 없다"는 뜻이다. 호출자는 그 상대를 후보에서 제외하고 다음으로
     * 넘어가야 한다 — 같은 후보를 다시 뽑으면 무한 루프가 된다.
     */
    private long executeInternalTrade(
        Order buyOrder,
        Order sellOrder,
        BigDecimal executionPrice,
        long requestedQuantity,
        Map<Long, Account> lockedAccounts
    ) {
        // 여기서 계좌 락을 새로 잡지 않는다. 잡는 순간 트랜잭션마다 획득 순서가 달라져 데드락이 생긴다.
        // 미리 id 순으로 잠가 둔 것만 쓰고, 없으면 이번 매칭의 상대가 아니다.
        Account buyerAccount = lockedAccounts.get(buyOrder.getAccount().getId());
        Account sellerAccount = lockedAccounts.get(sellOrder.getAccount().getId());
        if (buyerAccount == null || sellerAccount == null) {
            return 0L;
        }

        Holding sellerHolding = holdingRepository.findByAccountIdAndStockId(
            sellerAccount.getId(),
            sellOrder.getStock().getId()
        ).orElse(null);
        if (sellerHolding == null) {
            return 0L;
        }

        long executionQuantity = Math.min(
            requestedQuantity,
            Math.min(sellerHolding.getQuantity(), affordableQuantity(buyerAccount, executionPrice))
        );
        if (executionQuantity <= 0) {
            return 0L;
        }

        // 체결이 0이면 빈 보유 row를 만들지 않도록 캡 계산 뒤에 조회한다.
        Holding buyerHolding = holdingRepository.findByAccountIdAndStockId(
            buyerAccount.getId(),
            buyOrder.getStock().getId()
        ).orElseGet(() -> holdingRepository.save(Holding.create(buyerAccount, buyOrder.getStock())));

        BigDecimal executionAmount = money(executionPrice.multiply(BigDecimal.valueOf(executionQuantity)));
        BigDecimal costBasis = sellerHolding.sell(executionQuantity);
        BigDecimal realizedProfit = executionAmount.subtract(costBasis);
        buyerAccount.debitCash(executionAmount);
        sellerAccount.creditCash(executionAmount);
        sellerAccount.addRealizedProfit(realizedProfit);
        buyerHolding.buy(executionQuantity, executionPrice);
        buyOrder.fill(executionQuantity);
        sellOrder.fill(executionQuantity);

        // 분개는 엔티티를 모두 갱신한 뒤에 만든다. balanceAfter가 갱신 후 값이어야 하기 때문이다.
        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.cash(buyerAccount, executionAmount.negate()));
        postings.add(Posting.securities(buyerAccount, buyOrder.getStock(), executionAmount,
            executionQuantity, buyerHolding.getTotalPurchaseAmount()));
        postings.add(Posting.cash(sellerAccount, executionAmount));
        postings.add(Posting.securities(sellerAccount, sellOrder.getStock(), costBasis.negate(),
            -executionQuantity, sellerHolding.getTotalPurchaseAmount()));
        postings.add(Posting.of(sellerAccount, LedgerAccount.REALIZED_PNL, realizedProfit.negate()));

        Fees buyerFees = fees(executionPrice, executionQuantity);
        Fees sellerFees = fees(executionPrice, executionQuantity);
        addFeePostings(postings, buyerAccount, buyerFees);
        addFeePostings(postings, sellerAccount, sellerFees);

        String tradeId = UUID.randomUUID().toString();
        LedgerTransaction ledgerTransaction = ledgerPostingService.post(
            LedgerTransactionType.TRADE,
            "FILL:" + tradeId,
            buyOrder.getStock().getSymbol() + " 내부 체결",
            postings
        );

        createExecution(buyOrder, executionPrice, executionQuantity, buyerFees, tradeId, ledgerTransaction);
        createExecution(sellOrder, executionPrice, executionQuantity, sellerFees, tradeId, ledgerTransaction);
        return executionQuantity;
    }

    /** 이 가격에 몇 주까지 살 수 있는지. 잔고가 음수이거나 가격이 유효하지 않으면 0. */
    private long affordableQuantity(Account account, BigDecimal executionPrice) {
        if (executionPrice == null || executionPrice.signum() <= 0) {
            return 0L;
        }
        return account.getCashBalance()
            .divide(executionPrice, 0, RoundingMode.DOWN)
            .longValue();
    }

    private void executeExternalBuy(
        Order buyOrder,
        Account buyerAccount,
        Holding buyerHolding,
        BigDecimal executionPrice,
        Long executionQuantity
    ) {
        BigDecimal executionAmount = money(executionPrice.multiply(BigDecimal.valueOf(executionQuantity)));
        // 호출자가 affordableQuantity()로 이미 캡을 씌우므로 도달할 수 없다.
        // 남겨두는 이유는 거절 조건이어서가 아니라, 캡 계산이 깨졌다는 신호이기 때문이다.
        if (buyerAccount.getCashBalance().compareTo(executionAmount) < 0) {
            throw new IllegalArgumentException("주문 가능 금액이 부족합니다.");
        }

        buyerAccount.debitCash(executionAmount);
        buyerHolding.buy(executionQuantity, executionPrice);
        buyOrder.fill(executionQuantity);

        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.cash(buyerAccount, executionAmount.negate()));
        postings.add(Posting.securities(buyerAccount, buyOrder.getStock(), executionAmount,
            executionQuantity, buyerHolding.getTotalPurchaseAmount()));

        Fees buyerFees = fees(executionPrice, executionQuantity);
        addFeePostings(postings, buyerAccount, buyerFees);

        String tradeId = UUID.randomUUID().toString();
        LedgerTransaction ledgerTransaction = ledgerPostingService.post(
            LedgerTransactionType.TRADE,
            "FILL:" + tradeId,
            buyOrder.getStock().getSymbol() + " 매수",
            postings
        );
        createExecution(buyOrder, executionPrice, executionQuantity, buyerFees, tradeId, ledgerTransaction);
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
        BigDecimal realizedProfit = executionAmount.subtract(costBasis);

        sellerAccount.creditCash(executionAmount);
        sellerAccount.addRealizedProfit(realizedProfit);
        sellOrder.fill(executionQuantity);

        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.cash(sellerAccount, executionAmount));
        postings.add(Posting.securities(sellerAccount, sellOrder.getStock(), costBasis.negate(),
            -executionQuantity, sellerHolding.getTotalPurchaseAmount()));
        postings.add(Posting.of(sellerAccount, LedgerAccount.REALIZED_PNL, realizedProfit.negate()));

        Fees sellerFees = fees(executionPrice, executionQuantity);
        addFeePostings(postings, sellerAccount, sellerFees);

        String tradeId = UUID.randomUUID().toString();
        LedgerTransaction ledgerTransaction = ledgerPostingService.post(
            LedgerTransactionType.TRADE,
            "FILL:" + tradeId,
            sellOrder.getStock().getSymbol() + " 매도",
            postings
        );
        createExecution(sellOrder, executionPrice, executionQuantity, sellerFees, tradeId, ledgerTransaction);
    }

    private Order bestInternalSellOrder(
        Order buyOrder,
        Collection<Long> excludedOrderIds,
        Collection<Long> lockedAccountIds
    ) {
        return orderRepository.findMatchableSellOrders(
            excludedOrderIds,
            lockedAccountIds,
            buyOrder.getAccount().getId(),
            buyOrder.getStock().getId(),
            limitPrice(buyOrder),
            MATCHABLE_STATUSES,
            FIRST_MATCHABLE_ORDER
        ).stream().findFirst().orElse(null);
    }

    private Order bestInternalBuyOrder(
        Order sellOrder,
        Collection<Long> excludedOrderIds,
        Collection<Long> lockedAccountIds
    ) {
        return orderRepository.findMatchableBuyOrders(
            excludedOrderIds,
            lockedAccountIds,
            sellOrder.getAccount().getId(),
            sellOrder.getStock().getId(),
            limitPrice(sellOrder),
            MATCHABLE_STATUSES,
            FIRST_MATCHABLE_ORDER
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

    private Execution createExecution(
        Order order,
        BigDecimal executionPrice,
        Long executionQuantity,
        Fees fees,
        String tradeId,
        LedgerTransaction ledgerTransaction
    ) {
        return executionRepository.save(Execution.builder()
            .order(order)
            .executionPrice(executionPrice)
            .executionQuantity(executionQuantity)
            .commission(fees.commission())
            .tax(fees.tax())
            .tradeId(tradeId)
            .ledgerTransaction(ledgerTransaction)
            .build());
    }

    private Fees fees(BigDecimal executionPrice, long executionQuantity) {
        return new Fees(
            money(commissionCalculator.calculateCommission(executionPrice, executionQuantity)),
            money(commissionCalculator.calculateTax(executionPrice, executionQuantity))
        );
    }

    /**
     * 수수료·세금을 현금에서 차감하고 비용 분개를 붙인다.
     *
     * <p>0이면 아무것도 하지 않는다 — 0원 분개는 정보가 없다. 현재 {@code ZeroCommissionCalculator}라
     * 항상 이 경로다.
     *
     * <p><b>주의</b>: 0이 아닌 계산기로 바꾸면 {@code affordableQuantity()}의 잔고 캡이 수수료를
     * 고려하지 않아 현금이 모자랄 수 있다. 접수 시점 구속액은 이미 수수료를 포함하지만 체결 시점 캡은
     * 아직 아니다. 수수료 정책을 켜기 전에 그 캡을 함께 고쳐야 한다.
     */
    private void addFeePostings(List<Posting> postings, Account account, Fees fees) {
        BigDecimal total = fees.total();
        if (total.signum() <= 0) {
            return;
        }

        account.debitCash(total);
        postings.add(Posting.cash(account, total.negate()));
        if (fees.commission().signum() > 0) {
            postings.add(Posting.of(account, LedgerAccount.FEE, fees.commission()));
        }
        if (fees.tax().signum() > 0) {
            postings.add(Posting.of(account, LedgerAccount.TAX, fees.tax()));
        }
    }

    private record Fees(BigDecimal commission, BigDecimal tax) {

        BigDecimal total() {
            return commission.add(tax);
        }
    }

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
