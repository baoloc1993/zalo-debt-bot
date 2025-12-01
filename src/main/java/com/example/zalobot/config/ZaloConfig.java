package com.example.zalobot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@Data
public class ZaloConfig {
    private static final Logger logger = LoggerFactory.getLogger(ZaloConfig.class);
    @Value("${zalo.app.id}")
    private String appId;
    @Value("${zalo.app.secret}")
    private String appSecret;
    @Value("${zalo.access.token}")
    private String accessToken;
    @Value("${zalo.refresh.token}")
    private String refreshToken;
    @Value("${zalo.redirect.uri}")
    private String redirectUri;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        logger.debug("Getting refresh token: {}", refreshToken != null ? refreshToken.substring(0, Math.min(refreshToken.length(), 10)) + "..." : "null");
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
        logger.debug("Setting refresh token: {}", refreshToken != null ? refreshToken.substring(0, Math.min(refreshToken.length(), 10)) + "..." : "null");
    }
}