package com.example.zalobot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Data
public class ZaloConfig {
    @Value("${zalo.app.id}")
    private String appId;
    @Value("${zalo.app.secret}")
    private String appSecret;
    private String accessToken;
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
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}