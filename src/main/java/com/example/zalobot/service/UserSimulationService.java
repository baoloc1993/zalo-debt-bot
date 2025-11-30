package com.example.zalobot.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class UserSimulationService {

    private final ZaloService zaloService;

    public UserSimulationService(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    public Mono<Void> simulateIncomingMessage(String senderId, String messageText) {
        System.out.println("SIMULATION: User " + senderId + " sends message: " + messageText);
        return zaloService.processIncomingZaloMessage(senderId, messageText)
                .then(); // Convert to Mono<Void> as we don't need the return value for simulation
    }

    public Mono<Void> simulateConfirmationResponse(String senderId, String response) {
        System.out.println("SIMULATION: User " + senderId + " responds to confirmation: " + response);
        zaloService.handleConfirmationResponse(senderId, response);
        return Mono.empty();
    }
}