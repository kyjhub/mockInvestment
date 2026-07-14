package com.papertrade.paper_trading.Entity;

import com.papertrade.paper_trading.Enum.Currency;
import com.papertrade.paper_trading.Enum.RateChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
    name = "exchange_rates",
    uniqueConstraints = @UniqueConstraint(
        columnNames = {"base_currency", "quote_currency", "valid_from"}
    )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_currency", length = 10, nullable = false)
    private Currency baseCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "quote_currency", length = 10, nullable = false)
    private Currency quoteCurrency;

    @Column(precision = 19, scale = 6, nullable = false)
    private BigDecimal rate;

    @Column(name = "mid_rate", precision = 19, scale = 6, nullable = false)
    private BigDecimal midRate;

    @Column(name = "basis_point", precision = 19, scale = 6, nullable = false)
    private BigDecimal basisPoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_change_type", length = 20, nullable = false)
    private RateChangeType rateChangeType;

    @Column(name = "valid_from", nullable = false)
    private OffsetDateTime validFrom;

    @Column(name = "valid_until", nullable = false)
    private OffsetDateTime validUntil;
}
