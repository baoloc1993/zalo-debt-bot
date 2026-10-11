package io.github.baoloc1993.zalobot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Zalo Official Account settings, populated from environment variables
 * (see {@code application.properties}).
 */
@Configuration
@Data
public class ZaloConfig {

    @Value("${zalo.app.id}")
    private String appId;

    @Value("${zalo.app.secret}")
    private String appSecret;

    @Value("${zalo.oa.id}")
    private String oaId;

    /** Comma-separated Zalo user IDs polled by {@link ZaloScheduler}; may be empty. */
    @Value("${zalo.scheduled.users:}")
    private String scheduledUsers;
}
