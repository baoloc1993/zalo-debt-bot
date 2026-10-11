package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.ZaloToken;
import io.github.baoloc1993.zalobot.repository.ZaloTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ZaloTokenService {

    private static final Logger logger = LoggerFactory.getLogger(ZaloTokenService.class);

    private final ZaloTokenRepository zaloTokenRepository;

    public ZaloTokenService(ZaloTokenRepository zaloTokenRepository) {
        this.zaloTokenRepository = zaloTokenRepository;
    }

    @Transactional
    public ZaloToken saveToken(String accessToken, String refreshToken, Long expiresIn) {
        ZaloToken zaloToken = new ZaloToken();
        zaloToken.setAccessToken(accessToken);
        zaloToken.setRefreshToken(refreshToken);
        zaloToken.setExpiresIn(expiresIn);
        zaloToken.setCreatedAt(LocalDateTime.now());
        return zaloTokenRepository.save(zaloToken);
    }

    public Optional<ZaloToken> getLatestToken() {
        logger.info("Attempting to retrieve latest ZaloToken from database.");
        Optional<ZaloToken> latestToken = zaloTokenRepository.findTopByOrderByIdDesc();
        latestToken.ifPresent(token -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiresAt = token.getCreatedAt().plusSeconds(token.getExpiresIn());
            logger.info("ZaloToken retrieved: expiresIn={}, createdAt={}, now={}, expiresAt={}, isExpired={}",
                    token.getExpiresIn(), token.getCreatedAt(), now, expiresAt, token.isAccessTokenExpired());
        });
        return latestToken;
    }

    public boolean isAccessTokenExpired(ZaloToken token) {
        return token.isAccessTokenExpired();
    }
}