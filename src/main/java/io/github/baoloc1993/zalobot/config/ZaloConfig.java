package io.github.baoloc1993.zalobot.config;

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

    @Value("${zalo.oa.id}")
    private String oaId;

    @Value("${zalo.scheduled.users:}")
    private String scheduledUsers;

    public String getAppId() {
        return appId;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public String getOaId() {
        return oaId;
    }

    public String getScheduledUsers() {
        return scheduledUsers;
    }
}