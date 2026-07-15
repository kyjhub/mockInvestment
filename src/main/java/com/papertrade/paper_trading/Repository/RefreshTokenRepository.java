package com.papertrade.paper_trading.Repository;

import com.papertrade.paper_trading.Entity.RefreshToken;
import com.papertrade.paper_trading.Entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void deleteByUser(User user);
}
