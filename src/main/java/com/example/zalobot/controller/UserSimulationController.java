package com.example.zalobot.controller;

import com.example.zalobot.service.UserSimulationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/simulate")
public class UserSimulationController {

    private final UserSimulationService userSimulationService;

    public UserSimulationController(UserSimulationService userSimulationService) {
        this.userSimulationService = userSimulationService;
    }

    @PostMapping("/message")
    public Mono<String> simulateMessage(@RequestParam String senderId, @RequestParam String messageText) {
        return userSimulationService.simulateIncomingMessage(senderId, messageText)
                .thenReturn("Simulation of incoming message sent successfully.");
    }

    @PostMapping("/confirmation")
    public Mono<String> simulateConfirmation(@RequestParam String senderId, @RequestParam String response) {
        return userSimulationService.simulateConfirmationResponse(senderId, response)
                .thenReturn("Simulation of confirmation response sent successfully.");
    }
}