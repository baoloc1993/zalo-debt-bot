package io.github.baoloc1993.zalobot.config;

import io.github.baoloc1993.zalobot.service.ZaloService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically polls the latest message of each user listed in {@code zalo.scheduled.users}.
 * <p>
 * A tick is skipped while the previous run is still in flight. Even without this guard,
 * messages are de-duplicated by Zalo message id in {@code InboundMessageRegistry}.
 */
@Component
@EnableScheduling
public class ZaloScheduler {

    private static final Logger logger = LoggerFactory.getLogger(ZaloScheduler.class);

    private final ZaloService zaloService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ZaloScheduler(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    @Scheduled(fixedRate = 60000) // every 60 seconds
    public void readMessages() {
        if (!running.compareAndSet(false, true)) {
            logger.info("Previous message polling run is still in progress; skipping this tick.");
            return;
        }
        try {
            zaloService.readMessages()
                    .doFinally(signal -> running.set(false))
                    .subscribe(
                            result -> logger.debug("Polling result: {}", result),
                            error -> logger.warn("Message polling failed: {}", error.toString()));
        } catch (RuntimeException e) {
            running.set(false);
            logger.warn("Message polling could not be started: {}", e.toString());
        }
    }
}
