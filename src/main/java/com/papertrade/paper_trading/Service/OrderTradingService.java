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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTradingService {

    private static final int MONEY_SCALE = 2;
    private static final int PRICE_SCALE = 4;
    private static final String PRICE_OUT_OF_BAND = "주문가격이 허용 범위를 벗어났습니다.";

    /**
     * 예수금·보유 수량을 묶어 두는 주문. 접수 검증 대기 주문도 들어간다 — 지정가 매수는 접수할 때 이미
     * 구속액 검증을 통과했고, 매도는 수량을 묶어야 같은 주식을 두 번 팔지 못한다.
     * 가격이 아직 없는 시장가 매수는 구속 단가가 {@code null}이라 합계에 들어가지 않는다.
     */
    private static final List<OrderStatus> RESERVING_STATUSES = List.of(
        OrderStatus.AWAITING_PRICE,
        OrderStatus.PENDING,
        OrderStatus.PARTIALLY_FILLED
    );

    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final HoldingRepository holdingRepository;
    private final PriceService priceService;
    private final CommissionCalculator commissionCalculator;
    private final SymbolMatchRequestedStreamPublisher symbolMatchRequestedStreamPublisher;
    private final PlatformTransactionManager transactionManager;

    /**
     * 지정가 주문가격이 현재가에서 얼마나 벗어날 수 있는지.
     *
     * <p>미국 시장에는 일일 가격제한폭 제도가 없으므로 이건 규제 한도가 아니라 <b>오입력 방지</b>다.
     * 저가 매수·고가 매도를 걸어 두는 정상 주문을 막지 않도록 넉넉하게 잡는다.
     */
    @Value("${order.price-band.margin:0.5}")
    private BigDecimal priceBandMargin;

    /**
     * 시장가 주문을 지정가로 바꿀 때 현재가에 더하고(매수) 빼는(매도) 비율.
     *
     * <p>국내 증권사가 미국 주식 시장가 주문을 처리하는 방식과 같다. 미국 거래소에 그대로 낼 수 있는
     * 시장가가 없어 직전 체결가 ±10%의 지정가로 낸다. 이 가격 안의 반대편 호가는 모두 소비하고,
     * 남은 수량은 이 가격에서 대기한다.
     */
    @Value("${order.market-price.margin:0.1}")
    private BigDecimal marketPriceMargin;

    /**
     * 주문을 접수한다. 외부 호출을 트랜잭션 밖에 두기 위해 세 단계로 나뉜다.
     *
     * <ol>
     *   <li>짧은 읽기 트랜잭션 — 계좌·종목 확인과 멱등 재요청 응답</li>
     *   <li>트랜잭션 밖 — 현재가 조회. 캐시에 없으면 토스를 부른다</li>
     *   <li>쓰기 트랜잭션 — 계좌 락, 구속액 검증, 저장</li>
     * </ol>
     *
     * <p>한 트랜잭션으로 묶으면 토스 응답을 기다리는 동안 DB 커넥션을 쥐고 있게 된다. 트랜잭션은 시작할 때
     * 커넥션을 가져오므로 락을 늦게 잡는 것만으로는 부족하다. 커넥션 풀은 매칭 엔진과 공유한다.
     *
     * <p>현재가를 구하지 못해도 주문은 받는다. 그때는 접수 검증 대기({@code AWAITING_PRICE})로 저장하고,
     * {@link #confirmAwaitingPrice}가 현재가를 확보한 뒤 검증을 마친다.
     */
    public OrderResponse placeOrder(User user, OrderPlaceRequest request) {
        if (user == null) {
            throw new IllegalArgumentException("인증 정보가 필요합니다.");
        }

        validateRequest(request);

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        OrderResponse replay = transactionTemplate.execute(ignored -> {
            Account account = findAccount(user);
            stockRepository.findBySymbol(request.symbol())
                .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다."));
            return findIdempotentReplay(account, request).orElse(null);
        });
        if (replay != null) {
            return replay;
        }

        BigDecimal currentPrice = priceService.findPriceForOrder(request.symbol()).orElse(null);

        return transactionTemplate.execute(ignored -> placeWithLockedAccount(user, request, currentPrice));
    }

    private OrderResponse placeWithLockedAccount(User user, OrderPlaceRequest request, BigDecimal currentPrice) {
        Account account = findAccount(user);

        // 앞 단계와 이 트랜잭션 사이에 같은 요청이 먼저 저장됐을 수 있다.
        // 이미 접수된 주문을 재검증하면, 그 사이 잔고가 줄었을 때 같은 요청이 성공했다가 실패한다.
        Optional<OrderResponse> replay = findIdempotentReplay(account, request);
        if (replay.isPresent()) {
            return replay.get();
        }

        Stock stock = stockRepository.findBySymbol(request.symbol())
            .orElseThrow(() -> new IllegalArgumentException("종목을 찾을 수 없습니다."));

        boolean awaitingPrice = currentPrice == null;
        BigDecimal orderPrice = awaitingPrice ? request.price() : orderPrice(request, currentPrice);
        BigDecimal reservedUnitPrice = reservedUnitPrice(request.side(), orderPrice);

        // 락을 잡고 나서 구속액을 다시 집계한다. 잠그지 않으면 동시에 들어온 두 요청이
        // 같은 가용잔고를 읽고 둘 다 통과해 예수금을 넘긴다.
        Account lockedAccount = accountRepository.findByIdForUpdate(account.getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));
        if (request.side() == OrderSide.SELL) {
            validateSellableQuantity(lockedAccount, stock, request.quantity());
        } else if (reservedUnitPrice != null) {
            // 가격이 아직 없는 시장가 매수는 구속액을 모른다. confirmAwaitingPrice()에서 검증한다.
            validateOrderableAmount(lockedAccount, reservedUnitPrice, request.quantity());
        }

        String clientOrderId = normalizeClientOrderId(request.clientOrderId());
        Order order = awaitingPrice
            ? Order.createAwaitingPrice(lockedAccount, stock, clientOrderId, request.side(), request.orderType(),
                orderPrice, request.quantity(), reservedUnitPrice)
            : Order.create(lockedAccount, stock, clientOrderId, request.side(), request.orderType(),
                orderPrice, request.quantity(), reservedUnitPrice);
        orderRepository.saveAndFlush(order);
        if (!awaitingPrice) {
            publishAfterCommit(stock.getSymbol());
        }

        return toAcceptedResponse(order);
    }

    /**
     * 접수 검증 대기 주문을 현재가로 마저 검증한다. 통과하면 매칭 대상이 되고, 아니면 거절된다.
     *
     * <p>지정가는 가격 밴드를, 시장가는 주문가격을 정한 뒤 매수라면 주문가능금액을 검증한다.
     * 락 순서는 매칭 엔진과 같다 — 주문 row를 먼저, 계좌 row를 나중에 잡는다.
     *
     * @return 매칭 대상이 됐으면 {@code true}
     */
    public boolean confirmAwaitingPrice(Long orderId, BigDecimal currentPrice) {
        Boolean confirmed = new TransactionTemplate(transactionManager).execute(ignored -> {
            Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
            // 락을 기다리는 사이 사용자가 취소했거나 장 마감으로 실효됐을 수 있다.
            if (order == null || order.getStatus() != OrderStatus.AWAITING_PRICE) {
                return false;
            }

            try {
                BigDecimal orderPrice = order.getOrderType() == OrderType.LIMIT
                    ? validatedLimitPrice(order.getOrderPrice(), currentPrice)
                    : marketOrderPrice(order.getOrderSide(), currentPrice);
                BigDecimal reservedUnitPrice = reservedUnitPrice(order.getOrderSide(), orderPrice);

                if (order.getOrderType() == OrderType.MARKET && order.getOrderSide() == OrderSide.BUY) {
                    // 이 주문은 아직 구속 단가가 없어 집계에 들어가지 않으므로 자기 자신과 겹치지 않는다.
                    Account lockedAccount = accountRepository.findByIdForUpdate(order.getAccount().getId())
                        .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));
                    validateOrderableAmount(lockedAccount, reservedUnitPrice, order.getRemainingQuantity());
                }

                order.confirmPrice(orderPrice, reservedUnitPrice);
            } catch (IllegalArgumentException e) {
                order.reject(e.getMessage());
                return false;
            }

            publishAfterCommit(order.getStock().getSymbol());
            return true;
        });
        return Boolean.TRUE.equals(confirmed);
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

    private Account findAccount(User user) {
        return accountRepository.findByUserId(user.getId())
            .orElseThrow(() -> new IllegalArgumentException("계좌를 찾을 수 없습니다."));
    }

    private Optional<OrderResponse> findIdempotentReplay(Account account, OrderPlaceRequest request) {
        if (request.clientOrderId() == null || request.clientOrderId().isBlank()) {
            return Optional.empty();
        }
        return orderRepository.findByAccountIdAndClientOrderId(account.getId(), request.clientOrderId())
            .map(this::toAcceptedResponse);
    }

    private BigDecimal orderPrice(OrderPlaceRequest request, BigDecimal currentPrice) {
        return request.orderType() == OrderType.LIMIT
            ? validatedLimitPrice(request.price(), currentPrice)
            : marketOrderPrice(request.side(), currentPrice);
    }

    /**
     * 시장가 주문을 지정가로 바꾼 가격. 매수는 현재가 +10%, 매도는 −10%다.
     *
     * <p>반올림은 비율을 넘지 않는 쪽으로 한다. 매수는 내림, 매도는 올림이다.
     */
    private BigDecimal marketOrderPrice(OrderSide side, BigDecimal currentPrice) {
        return side == OrderSide.BUY
            ? currentPrice.multiply(BigDecimal.ONE.add(marketPriceMargin)).setScale(PRICE_SCALE, RoundingMode.DOWN)
            : currentPrice.multiply(BigDecimal.ONE.subtract(marketPriceMargin)).setScale(PRICE_SCALE, RoundingMode.UP);
    }

    /**
     * 지정가 주문가격이 현재가 ±{@code priceBandMargin} 안인지 본다.
     *
     * <p>막으려는 것은 오입력과 <b>가격을 스스로 정해 손익을 만들어내는 경로</b>다. 같은 계좌끼리의
     * 체결은 매칭 후보 조회에서 이미 막지만, 서로 다른 계좌가 터무니없는 가격에 맞붙는 것은 그것만으로
     * 막히지 않는다. 한쪽이 잃고 한쪽이 얻는 구조라 공짜는 아니지만, 계정을 여러 개 만들면 한 계정을
     * 희생시켜 다른 계정의 손익을 부풀릴 수 있다.
     *
     * <p>현재가를 모르면 이 검증을 건너뛰지 않는다. 주문을 접수 검증 대기로 받고 현재가를 확보한 뒤
     * 검증한다. 검증을 거치지 않은 지정가가 체결에 쓰이면 위 경로가 다시 열린다.
     */
    private BigDecimal validatedLimitPrice(BigDecimal limitPrice, BigDecimal currentPrice) {
        BigDecimal floor = currentPrice.multiply(BigDecimal.ONE.subtract(priceBandMargin));
        BigDecimal ceiling = currentPrice.multiply(BigDecimal.ONE.add(priceBandMargin));
        if (limitPrice.compareTo(floor) < 0 || limitPrice.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException(PRICE_OUT_OF_BAND);
        }
        return limitPrice;
    }

    /**
     * 매수 주문이 1주당 구속할 금액. 주문가격 그 자체다 — 지정가 주문은 그 가격보다 비싸게 체결되지 않는다.
     * 시장가도 접수할 때 지정가로 바뀌므로 같다. 매도 주문과 가격이 아직 없는 주문은 {@code null}이다.
     *
     * <p>파생값을 저장하는 것처럼 보이지만 주문의 불변 속성이라 드리프트가 생길 수 없고, 덕분에 구속액 집계가
     * 외부 시세 조회 없이 {@code orders} 한 테이블에서 순수 SQL로 끝난다.
     */
    private BigDecimal reservedUnitPrice(OrderSide side, BigDecimal orderPrice) {
        return side == OrderSide.BUY ? orderPrice : null;
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
            order.getCloseReason(),
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
