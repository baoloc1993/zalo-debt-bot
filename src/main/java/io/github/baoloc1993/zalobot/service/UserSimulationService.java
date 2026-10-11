package io.github.baoloc1993.zalobot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class UserSimulationService {

    private static final Logger logger = LoggerFactory.getLogger(UserSimulationService.class);

    private final ZaloService zaloService;

    public UserSimulationService(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    public Mono<Void> simulateIncomingMessage(String senderId, String messageText) {
        logger.debug("SIMULATION: User {} sends message: {}", senderId, messageText);
        System.out.println("SYSTEM_OUT_SIMULATION: User " + senderId + " sends message: " + messageText);
                return zaloService.processIncomingZaloMessage(senderId, messageText, "simulated_message_id")
                .then(Mono.empty()); // Ensure subscription and return Mono<Void>
    }


}