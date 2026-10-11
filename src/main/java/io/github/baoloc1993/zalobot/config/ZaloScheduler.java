package io.github.baoloc1993.zalobot.config;

import io.github.baoloc1993.zalobot.service.ZaloService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically polls the latest message of each user listed in {@code zalo.scheduled.users}.
 */
@Component
@EnableScheduling
public class ZaloScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ZaloScheduler.class);

    private final ZaloService zaloService;

    public ZaloScheduler(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    @Scheduled(fixedRate = 60000) // every 60 seconds
    public void readMessages() {
        logger.info("Scheduled task: Reading Zalo messages...");
        zaloService.readMessages().subscribe();
    }
}
