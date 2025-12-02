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



}