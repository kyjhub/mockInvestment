package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.Account;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") Long accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.user.id = :userId")
    Optional<Account> findByUserIdForUpdate(@Param("userId") Long userId);

    /**
     * 총자산 평가액만 갱신한다.
     *
     * <p>엔티티를 읽어 필드를 바꾸는 대신 단일 컬럼 update를 쓰는 이유는 <b>덮어쓰기 때문</b>이다.
     * 평가는 락을 잡지 않으므로, 엔티티를 통째로 flush하면 그 사이 체결이 바꾼 {@code cash_balance}를
     * 오래된 값으로 되돌릴 수 있다. 이 컬럼은 평가 batch 말고는 아무도 쓰지 않는다.
     *
     * <p>락을 잡지 않는 이유는 이 값이 표시용이기 때문이다. 잠그면 평가가 체결을 막는다.
     * 잠깐 어긋나도 다음 주기에 맞는다.
     */
    @Modifying
    @Transactional
    @Query("update Account a set a.totalAssetValue = :totalAssetValue where a.id = :accountId")
    void updateTotalAssetValue(
        @Param("accountId") Long accountId,
        @Param("totalAssetValue") BigDecimal totalAssetValue
    );
}
