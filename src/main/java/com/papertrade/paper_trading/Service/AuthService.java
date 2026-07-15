package com.papertrade.paper_trading.Service;

import com.papertrade.paper_trading.Dto.AuthTokenResponse;
import com.papertrade.paper_trading.Dto.LoginRequest;
import com.papertrade.paper_trading.Dto.SignupRequest;
import com.papertrade.paper_trading.Dto.TokenRefreshRequest;
import com.papertrade.paper_trading.Entity.RefreshToken;
import com.papertrade.paper_trading.Entity.User;
import com.papertrade.paper_trading.Enum.Role;
import com.papertrade.paper_trading.Enum.Status;
import com.papertrade.paper_trading.Repository.RefreshTokenRepository;
import com.papertrade.paper_trading.Repository.UserRepository;
import com.papertrade.paper_trading.Security.JwtTokenProvider;
import com.papertrade.paper_trading.Security.TokenHashService;
import io.jsonwebtoken.JwtException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenHashService tokenHashService;

    @Transactional
    public AuthTokenResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email is already registered");
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new IllegalArgumentException("Nickname is already registered");
        }

        User user = User.builder()
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .nickname(request.nickname())
            .role(Role.USER)
            .status(Status.ACTIVE)
            .build();

        User savedUser = userRepository.save(user);
        return issueTokens(savedUser);
    }

    @Transactional
    public AuthTokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
            .filter(foundUser -> foundUser.getStatus() == Status.ACTIVE)
            .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        return issueTokens(user);
    }

    @Transactional
    public AuthTokenResponse refresh(TokenRefreshRequest request) {
        String refreshToken = request.refreshToken();
        if (!isValidRefreshJwt(refreshToken)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        String tokenHash = tokenHashService.hash(refreshToken);
        RefreshToken savedToken = refreshTokenRepository.findByTokenHash(tokenHash)
            .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        LocalDateTime now = LocalDateTime.now();
        if (savedToken.isRevoked() || savedToken.isExpired(now)) {
            throw new IllegalArgumentException("Invalid refresh token");
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        User user = userRepository.findById(userId)
            .filter(foundUser -> foundUser.getStatus() == Status.ACTIVE)
            .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        savedToken.revoke(now);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = tokenHashService.hash(refreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
            .filter(token -> !token.isRevoked())
            .ifPresent(token -> token.revoke(LocalDateTime.now()));
    }

    private AuthTokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user);
        String refreshToken = jwtTokenProvider.createRefreshToken(user);

        refreshTokenRepository.save(RefreshToken.builder()
            .user(user)
            .tokenHash(tokenHashService.hash(refreshToken))
            .expiresAt(jwtTokenProvider.getExpiration(refreshToken))
            .build());

        return new AuthTokenResponse(TOKEN_TYPE, accessToken, refreshToken);
    }

    private boolean isValidRefreshJwt(String refreshToken) {
        try {
            return jwtTokenProvider.isRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
