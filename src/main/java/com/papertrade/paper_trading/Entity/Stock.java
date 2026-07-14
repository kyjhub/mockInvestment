package com.papertrade.paper_trading.Entity;

import com.papertrade.paper_trading.Enum.Market;
import com.papertrade.paper_trading.Enum.SecurityType;
import com.papertrade.paper_trading.Enum.StockStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Entity
@Table(name = "stocks")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 20, nullable = false, unique = true)
    private String symbol;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(name = "english_name", length = 100)
    private String englishName;

    @Column(name = "isin_code", length = 20, unique = true)
    private String isinCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private Market market;

    @Enumerated(EnumType.STRING)
    @Column(name = "security_type", length = 30, nullable = false)
    private SecurityType securityType;

    @Column(name = "is_common_share", nullable = false)
    private Boolean isCommonShare;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private StockStatus status;

    @Column(length = 10, nullable = false)
    private String currency;

    @Column(name = "list_date")
    private LocalDate listDate;

    @Column(name = "delist_date")
    private LocalDate delistDate;

    @Column(name = "shares_outstanding")
    private Long sharesOutstanding;

    @Column(name = "leverage_factor", precision = 10, scale = 4)
    private BigDecimal leverageFactor;
}
