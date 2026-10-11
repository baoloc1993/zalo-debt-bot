package io.github.baoloc1993.zalobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Service
@Profile("dev")
public class UserSimulationService {

    private static final Logger logger = LoggerFactory.getLogger(UserSimulationService.class);

    private final ZaloService zaloService;

    public UserSimulationService(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    /**
     * @param messageId Zalo message id to use; sending the same id twice exercises de-duplication.
     *                  A random id is generated when {@code null} or blank.
     */
    public Mono<Void> simulateIncomingMessage(String senderId, String messageText, String messageId) {
        String id = (messageId == null || messageId.isBlank()) ? "sim-" + UUID.randomUUID() : messageId;
        logger.debug("SIMULATION: User {} sends message {}: {}", senderId, id, messageText);
        return zaloService.processIncomingZaloMessage(senderId, messageText, id)
                .then();
    }
}