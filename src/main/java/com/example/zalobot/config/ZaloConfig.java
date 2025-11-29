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
    @Value("${zalo.access.token}")
    private String accessToken;
    @Value("${zalo.refresh.token}")
    private String refreshToken;
    @Value("${zalo.redirect.uri}")
    private String redirectUri;
}