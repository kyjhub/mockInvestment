package com.papertrade.paper_trading.Service;

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
import com.papertrade.paper_trading.Repository.AccountRepository;
import com.papertrade.paper_trading.Repository.ExecutionRepository;
import com.papertrade.paper_trading.Repository.HoldingRepository;
import com.papertrade.paper_trading.Repository.OrderRepository;
import com.papertrade.paper_trading.Repository.OrderRepository.MatchableOrder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.papertrade.paper_trading.Service.LedgerPostingService.Posting;
import com.papertrade.paper_trading.Service.TradingFees.Fees;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatchingEngineTransactionService {

    private static final int MONEY_SCALE = 2;
    private static final Pageable FIRST_MATCHABLE_ORDER = PageRequest.of(0, 1);
    private static final List<OrderStatus> MATCHABLE_STATUSES = List.of(
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final HoldingRepository holdingRepository;
    private final LedgerPostingService ledgerPostingService;
    private final OrderBookService orderBookService;
    private final TradingFees tradingFees;
    private final PlatformTransactionManager transactionManager;

    public void matchSymbol(String symbol) {
        List<MatchableOrder> matchableOrders = orderRepository.findMatchableOrdersBySymbol(symbol, MATCHABLE_STATUSES);
        for (MatchableOrder matchableOrder : matchableOrders) {
            // Toss 호출은 주문의 pessimistic lock을 잡기 전, transaction 밖에서 수행한다.
            // WebSocket 구독 종목이면 호출 자체가 없다 — 캐시가 곧 최신 상태다.
            OrderBookResponse orderBook = orderBookService.getOrderBookForMatching(symbol, matchableOrder.submittedAt());
            Long orderId = matchableOrder.id();
            try {
                matchOrder(orderId, orderBook);
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

    /**
     * 한 주문을 체결 가능한 만큼 끝까지 체결한다. <b>체결 한 건이 트랜잭션 한 건</b>이고 루프는 트랜잭션 밖에 있다.
     *
     * <p>이 구조가 필요한 이유는 <b>가격-시간 우선순위</b>다. 한 트랜잭션이 주문 전체를 처리하려면 잠글
     * 계좌를 시작 시점에 다 알아야 하고, 그러면 잠글 수를 제한할 수밖에 없다. 제한을 두는 순간 앞선 주문이
     * 체결 가능한 물량을 남겨 둔 채 멈추고, 뒤에 선 주문이 그 물량을 가져간다. 우선순위는 타협 대상이
     * 아니므로 제한을 없애야 하고, 그러려면 체결 단위로 잠가야 한다.
     *
     * <p>체결 한 건은 참가자 2명만 잠그므로 미리 알아낼 필요가 없다. 계좌 락 보유 시간도 주문 전체가
     * 아니라 체결 하나 수준으로 줄어든다.
     *
     * <p>종목 락이 같은 종목의 동시 매칭을 막으므로, 트랜잭션이 끊기는 사이에 다른 매칭이 끼어들지 않는다.
     */
    public void matchOrder(Long orderId, OrderBookResponse orderBook) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        MatchCursor cursor = MatchCursor.initial();
        while (true) {
            MatchCursor current = cursor;
            MatchCursor next = transactionTemplate.execute(ignored -> matchOnce(orderId, orderBook, current));
            if (next == null || next.finished()) {
                break;
            }
            cursor = next;
        }
    }

    /**
     * 체결 한 건.
     *
     * <p>락 획득 순서를 종류별로 고정한다 — <b>주문 row → 계좌 row(id 오름차순) → 보유 row</b>.
     * 모든 트랜잭션이 같은 순서를 따르므로 순환이 만들어지지 않는다.
     */
    private MatchCursor matchOnce(Long orderId, OrderBookResponse orderBook, MatchCursor cursor) {
        Order incomingOrder = orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        if (!MATCHABLE_STATUSES.contains(incomingOrder.getStatus()) || incomingOrder.getRemainingQuantity() == 0) {
            return cursor.finish();
        }

        return incomingOrder.getOrderSide() == OrderSide.BUY
            ? matchBuyOnce(incomingOrder, orderBook, cursor)
            : matchSellOnce(incomingOrder, orderBook, cursor);
    }

    /**
     * 체결 한 건에 참가하는 계좌를 <b>id 오름차순으로</b> 잠근다. 최대 두 개다.
     *
     * <p>모든 트랜잭션이 같은 순서로 잡으므로 순환이 만들어지지 않는다. 순환하려면 각 트랜잭션이 자기가
     * 이미 가진 것보다 작은 id를 기다려야 하는데, 오름차순이면 늘 큰 id만 기다린다 —
     * 따라가면 {@code r1 < r2 < ... < r1}이 되어 모순이다.
     */
    private Map<Long, Account> lockAccountsInIdOrder(Long... accountIds) {
        Map<Long, Account> locked = new HashMap<>();
        new TreeSet<>(Arrays.asList(accountIds)).forEach(accountId ->
            accountRepository.findByIdForUpdate(accountId)
                .ifPresent(account -> locked.put(accountId, account)));
        return locked;
    }

    private MatchCursor matchBuyOnce(Order buyOrder, OrderBookResponse orderBook, MatchCursor cursor) {
        List<OrderBookLevel> externalAsks = executableAskLevels(buyOrder, orderBook);
        Order internalSellOrder = bestInternalSellOrder(buyOrder, cursor.excludedOrderIds());
        OrderBookLevel externalAsk = externalLevel(externalAsks, cursor.externalIndex());

        if (internalSellOrder == null && externalAsk == null) {
            // 체결 상대가 아예 없다. 계좌 문제가 아니라 유동성 문제이므로 거절하지 않고 대기시킨다.
            return cursor.finish();
        }

        if (shouldUseInternalSell(internalSellOrder, externalAsk)) {
            BigDecimal executionPrice = internalSellOrder.getOrderPrice();
            Map<Long, Account> lockedAccounts = lockAccountsInIdOrder(
                buyOrder.getAccount().getId(), internalSellOrder.getAccount().getId());
            Account buyerAccount = requireAccount(lockedAccounts, buyOrder);

            long affordableQuantity = affordableQuantity(buyerAccount, buyOrder, executionPrice);
            if (affordableQuantity <= 0) {
                // 가장 싼 후보를 1주도 못 산다. 이후 후보는 가격이 비감소라 마찬가지다.
                buyOrder.reject("주문 가능 금액이 부족합니다.");
                return cursor.finish();
            }

            long requestedQuantity = Math.min(
                Math.min(buyOrder.getRemainingQuantity(), internalSellOrder.getRemainingQuantity()),
                affordableQuantity);
            long executedQuantity = executeInternalTrade(
                buyOrder, internalSellOrder, executionPrice, requestedQuantity, lockedAccounts);
            if (executedQuantity <= 0) {
                // 상대 매도자의 보유가 비어 있다. 같은 후보를 다시 뽑으면 무한 루프이므로 제외하고 계속한다.
                return cursor.exclude(internalSellOrder.getId());
            }
            return cursor;
        }

        Map<Long, Account> lockedAccounts = lockAccountsInIdOrder(buyOrder.getAccount().getId());
        Account buyerAccount = requireAccount(lockedAccounts, buyOrder);
        long affordableQuantity = affordableQuantity(buyerAccount, buyOrder, externalAsk.price());
        if (affordableQuantity <= 0) {
            buyOrder.reject("주문 가능 금액이 부족합니다.");
            return cursor.finish();
        }

        long availableExternalQuantity = externalAsk.volume() - cursor.consumedAtIndex();
        long executionQuantity = Math.min(
            Math.min(buyOrder.getRemainingQuantity(), availableExternalQuantity), affordableQuantity);
        if (executionQuantity <= 0) {
            return cursor.finish();
        }

        Holding buyerHolding = holdingRepository.findByAccountIdAndStockId(
            buyerAccount.getId(), buyOrder.getStock().getId()
        ).orElseGet(() -> holdingRepository.save(Holding.create(buyerAccount, buyOrder.getStock())));
        executeExternalBuy(buyOrder, buyerAccount, buyerHolding, externalAsk.price(), executionQuantity);

        return cursor.consumeExternal(executionQuantity, externalAsk.volume());
    }

    private MatchCursor matchSellOnce(Order sellOrder, OrderBookResponse orderBook, MatchCursor cursor) {
        // 팔 물량이 아예 없으면 상대가 있든 없든 이 주문은 체결될 수 없다. 유동성 문제가 아니라 계좌 문제다.
        // 락 없이 읽어도 되는 이유는 HoldingRepository.findForJudgementByAccountIdAndStockId 주석 참고.
        if (heldQuantity(sellOrder) <= 0) {
            sellOrder.reject("보유 수량이 부족합니다.");
            return cursor.finish();
        }

        List<OrderBookLevel> externalBids = executableBidLevels(sellOrder, orderBook);
        Order internalBuyOrder = bestInternalBuyOrder(sellOrder, cursor.excludedOrderIds());
        OrderBookLevel externalBid = externalLevel(externalBids, cursor.externalIndex());

        if (internalBuyOrder == null && externalBid == null) {
            // 유동성 문제다. 거절하지 않는다.
            return cursor.finish();
        }

        if (shouldUseInternalBuy(internalBuyOrder, externalBid)) {
            BigDecimal executionPrice = internalBuyOrder.getOrderPrice();
            Map<Long, Account> lockedAccounts = lockAccountsInIdOrder(
                sellOrder.getAccount().getId(), internalBuyOrder.getAccount().getId());
            Account sellerAccount = requireAccount(lockedAccounts, sellOrder);

            long sellableQuantity = sellableQuantity(sellerAccount, sellOrder);
            if (sellableQuantity <= 0) {
                // 체결 상대는 있는데 팔 물량이 없다.
                sellOrder.reject("보유 수량이 부족합니다.");
                return cursor.finish();
            }

            long requestedQuantity = Math.min(
                Math.min(sellOrder.getRemainingQuantity(), internalBuyOrder.getRemainingQuantity()),
                sellableQuantity);
            long executedQuantity = executeInternalTrade(
                internalBuyOrder, sellOrder, executionPrice, requestedQuantity, lockedAccounts);
            if (executedQuantity <= 0) {
                // 상대 매수자의 잔고가 부족하다. 제외하고 다음 후보로.
                return cursor.exclude(internalBuyOrder.getId());
            }
            return cursor;
        }

        Map<Long, Account> lockedAccounts = lockAccountsInIdOrder(sellOrder.getAccount().getId());
        Account sellerAccount = requireAccount(lockedAccounts, sellOrder);
        long sellableQuantity = sellableQuantity(sellerAccount, sellOrder);
        if (sellableQuantity <= 0) {
            sellOrder.reject("보유 수량이 부족합니다.");
            return cursor.finish();
        }

        long availableExternalQuantity = externalBid.volume() - cursor.consumedAtIndex();
        long executionQuantity = Math.min(
            Math.min(sellOrder.getRemainingQuantity(), availableExternalQuantity), sellableQuantity);
        if (executionQuantity <= 0) {
            return cursor.finish();
        }

        Holding sellerHolding = holdingRepository.findByAccountIdAndStockId(
            sellerAccount.getId(), sellOrder.getStock().getId()).orElseThrow(
                () -> new IllegalArgumentException("보유 수량이 부족합니다."));
        executeExternalSell(sellOrder, sellerAccount, sellerHolding, externalBid.price(), executionQuantity);

        return cursor.consumeExternal(executionQuantity, externalBid.volume());
    }

    private Account requireAccount(Map<Long, Account> lockedAccounts, Order order) {
        Account account = lockedAccounts.get(order.getAccount().getId());
        if (account == null) {
            throw new IllegalArgumentException("계좌를 찾을 수 없습니다.");
        }
        return account;
    }

    /** 지금 이 계좌가 이 종목으로 팔 수 있는 수량. 보유 row가 없으면 0이다. */
    private long sellableQuantity(Account sellerAccount, Order sellOrder) {
        return holdingRepository.findByAccountIdAndStockId(sellerAccount.getId(), sellOrder.getStock().getId())
            .map(Holding::getQuantity)
            .orElse(0L);
    }

    /** 거절 판정용 보유 수량. 락을 잡지 않는다. */
    private long heldQuantity(Order sellOrder) {
        return holdingRepository.findForJudgementByAccountIdAndStockId(
                sellOrder.getAccount().getId(), sellOrder.getStock().getId())
            .map(Holding::getQuantity)
            .orElse(0L);
    }

    /**
     * 주문 하나를 체결해 나가는 동안 트랜잭션 밖에서 들고 다니는 상태.
     *
     * <p>트랜잭션 경계를 체결 단위로 쪼개면 loop 변수를 영속성 컨텍스트에 둘 수 없다.
     * 외부 호가 소비 위치와 체결 불가로 판명된 상대는 트랜잭션이 rollback돼도 그대로여야 하므로
     * 값으로 넘긴다.
     */
    private record MatchCursor(
        int externalIndex,
        long consumedAtIndex,
        Set<Long> excludedOrderIds,
        boolean finished
    ) {

        static MatchCursor initial() {
            return new MatchCursor(0, 0L, Set.of(), false);
        }

        MatchCursor finish() {
            return new MatchCursor(externalIndex, consumedAtIndex, excludedOrderIds, true);
        }

        MatchCursor exclude(Long orderId) {
            Set<Long> excluded = new HashSet<>(excludedOrderIds);
            excluded.add(orderId);
            return new MatchCursor(externalIndex, consumedAtIndex, Set.copyOf(excluded), false);
        }

        /** 외부 호가 level을 소비한다. 그 level을 다 쓰면 다음 level로 넘어간다. */
        MatchCursor consumeExternal(long executedQuantity, long levelVolume) {
            long consumed = consumedAtIndex + executedQuantity;
            return consumed >= levelVolume
                ? new MatchCursor(externalIndex + 1, 0L, excludedOrderIds, false)
                : new MatchCursor(externalIndex, consumed, excludedOrderIds, false);
        }
    }

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
            Math.min(sellerHolding.getQuantity(), affordableQuantity(buyerAccount, buyOrder, executionPrice))
        );
        if (executionQuantity <= 0) {
            return 0L;
        }

        // 체결이 0이면 빈 보유 row를 만들지 않도록 캡 계산 뒤에 조회한다.
        Holding buyerHolding = holdingRepository.findByAccountIdAndStockId(
            buyerAccount.getId(),
            buyOrder.getStock().getId()
        ).orElseGet(() -> holdingRepository.save(Holding.create(buyerAccount, buyOrder.getStock())));

        BigDecimal tradeAmount = tradeAmount(executionPrice, executionQuantity);
        BigDecimal executionAmount = money(tradeAmount);
        // 수수료는 주문의 누적값으로 계산하므로 fill()로 누적값을 바꾸기 전에 구한다.
        Fees buyerFees = tradingFees.nextFill(buyOrder, tradeAmount);
        Fees sellerFees = tradingFees.nextFill(sellOrder, tradeAmount);
        BigDecimal costBasis = sellerHolding.sell(executionQuantity);
        BigDecimal realizedProfit = executionAmount.subtract(costBasis);
        buyerAccount.debitCash(executionAmount);
        sellerAccount.creditCash(executionAmount);
        sellerAccount.addRealizedProfit(realizedProfit);
        buyerHolding.buy(executionQuantity, executionPrice);
        buyOrder.fill(executionQuantity, tradeAmount, buyerFees.commission(), buyerFees.tax());
        sellOrder.fill(executionQuantity, tradeAmount, sellerFees.commission(), sellerFees.tax());

        // 분개는 엔티티를 모두 갱신한 뒤에 만든다. balanceAfter가 갱신 후 값이어야 하기 때문이다.
        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.cash(buyerAccount, executionAmount.negate()));
        postings.add(Posting.securities(buyerAccount, buyOrder.getStock(), executionAmount,
            executionQuantity, buyerHolding.getTotalPurchaseAmount()));
        postings.add(Posting.cash(sellerAccount, executionAmount));
        postings.add(Posting.securities(sellerAccount, sellOrder.getStock(), costBasis.negate(),
            -executionQuantity, sellerHolding.getTotalPurchaseAmount()));
        postings.add(Posting.of(sellerAccount, LedgerAccount.REALIZED_PNL, realizedProfit.negate()));

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

    /**
     * 이 가격에 몇 주까지 살 수 있는지. <b>수수료·세금까지 낸 뒤에도 예수금이 음수가 되지 않는</b> 최대 수량이다.
     * 잔고가 음수이거나 가격이 유효하지 않으면 0.
     *
     * <p>체결대금만으로 수량을 정하면 수수료가 0이 아닐 때 현금이 모자란다. 그러면 {@code cash_balance >= 0}
     * 제약에 걸려 체결 트랜잭션이 통째로 실패하고, 같은 주문이 매번 같은 자리에서 실패한다.
     *
     * <p>수수료는 이 주문의 누적 체결금액으로 매기고 센트에서 반올림하므로({@link TradingFees}) 수량을 식으로
     * 역산하면 반올림 경계에서 1주씩 어긋난다. 그래서 수수료 없이 살 수 있는 수량을 상한으로 두고 이분
     * 탐색한다. 수량이 늘면 총비용이 줄지 않으므로 성립한다. 수수료가 0이면 상한에서 바로 끝난다.
     */
    private long affordableQuantity(Account account, Order buyOrder, BigDecimal executionPrice) {
        if (executionPrice == null || executionPrice.signum() <= 0) {
            return 0L;
        }
        BigDecimal cash = account.getCashBalance();
        long upperBound = cash.divide(executionPrice, 0, RoundingMode.DOWN).longValue();
        if (upperBound <= 0) {
            return 0L;
        }
        if (purchaseCost(buyOrder, executionPrice, upperBound).compareTo(cash) <= 0) {
            return upperBound;
        }

        long affordable = 0L;
        long unaffordable = upperBound;
        while (unaffordable - affordable > 1) {
            long candidate = affordable + (unaffordable - affordable) / 2;
            if (purchaseCost(buyOrder, executionPrice, candidate).compareTo(cash) <= 0) {
                affordable = candidate;
            } else {
                unaffordable = candidate;
            }
        }
        return affordable;
    }

    /** 이 주문이 이 수량을 더 사면 실제로 빠져나가는 현금. 체결 경로와 같은 규칙으로 계산한다. */
    private BigDecimal purchaseCost(Order buyOrder, BigDecimal executionPrice, long quantity) {
        BigDecimal tradeAmount = tradeAmount(executionPrice, quantity);
        return money(tradeAmount).add(tradingFees.nextFill(buyOrder, tradeAmount).total());
    }

    /** 체결가 × 수량. 반올림하지 않는다 — 수수료의 기준이 되는 금액이다. */
    private BigDecimal tradeAmount(BigDecimal executionPrice, long quantity) {
        return executionPrice.multiply(BigDecimal.valueOf(quantity));
    }

    private void executeExternalBuy(
        Order buyOrder,
        Account buyerAccount,
        Holding buyerHolding,
        BigDecimal executionPrice,
        Long executionQuantity
    ) {
        BigDecimal tradeAmount = tradeAmount(executionPrice, executionQuantity);
        BigDecimal executionAmount = money(tradeAmount);
        Fees buyerFees = tradingFees.nextFill(buyOrder, tradeAmount);
        // 호출자가 affordableQuantity()로 이미 캡을 씌우므로 도달할 수 없다.
        // 남겨두는 이유는 거절 조건이어서가 아니라, 캡 계산이 깨졌다는 신호이기 때문이다.
        if (buyerAccount.getCashBalance().compareTo(executionAmount.add(buyerFees.total())) < 0) {
            throw new IllegalArgumentException("주문 가능 금액이 부족합니다.");
        }

        buyerAccount.debitCash(executionAmount);
        buyerHolding.buy(executionQuantity, executionPrice);
        buyOrder.fill(executionQuantity, tradeAmount, buyerFees.commission(), buyerFees.tax());

        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.cash(buyerAccount, executionAmount.negate()));
        postings.add(Posting.securities(buyerAccount, buyOrder.getStock(), executionAmount,
            executionQuantity, buyerHolding.getTotalPurchaseAmount()));

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
        BigDecimal tradeAmount = tradeAmount(executionPrice, executionQuantity);
        BigDecimal executionAmount = money(tradeAmount);
        Fees sellerFees = tradingFees.nextFill(sellOrder, tradeAmount);
        BigDecimal costBasis = sellerHolding.sell(executionQuantity);
        BigDecimal realizedProfit = executionAmount.subtract(costBasis);

        sellerAccount.creditCash(executionAmount);
        sellerAccount.addRealizedProfit(realizedProfit);
        sellOrder.fill(executionQuantity, tradeAmount, sellerFees.commission(), sellerFees.tax());

        List<Posting> postings = new ArrayList<>();
        postings.add(Posting.cash(sellerAccount, executionAmount));
        postings.add(Posting.securities(sellerAccount, sellOrder.getStock(), costBasis.negate(),
            -executionQuantity, sellerHolding.getTotalPurchaseAmount()));
        postings.add(Posting.of(sellerAccount, LedgerAccount.REALIZED_PNL, realizedProfit.negate()));

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

    private Order bestInternalSellOrder(Order buyOrder, Collection<Long> excludedOrderIds) {
        return orderRepository.findMatchableSellOrders(
            excludedOrderIds,
            buyOrder.getAccount().getId(),
            buyOrder.getStock().getId(),
            limitPrice(buyOrder),
            MATCHABLE_STATUSES,
            FIRST_MATCHABLE_ORDER
        ).stream().findFirst().orElse(null);
    }

    private Order bestInternalBuyOrder(Order sellOrder, Collection<Long> excludedOrderIds) {
        return orderRepository.findMatchableBuyOrders(
            excludedOrderIds,
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

    /**
     * 수수료·세금을 현금에서 차감하고 비용 분개를 붙인다.
     *
     * <p>0이면 아무것도 하지 않는다 — 0원 분개는 정보가 없다. 운영은 요율 0이라 항상 이 경로지만,
     * 체결 경로는 수수료가 있다고 가정하고 동작한다.
     *
     * <p>매수자는 {@code affordableQuantity()}가 수수료까지 낼 수 있는 수량으로 캡을 씌운다. 매도자는 받은
     * 체결대금에서 수수료를 내므로 캡이 없다 — 수수료가 체결대금보다 큰 계산기가 아니라면 음수가 될 수 없다.
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

    private BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
