package com.example.zalobot.controller;

import com.example.zalobot.service.ZaloService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class ZaloController {

    private final ZaloService zaloService;

    public ZaloController(ZaloService zaloService) {
        this.zaloService = zaloService;
    }

    @GetMapping("/zalo/profile")
    public Mono<String> getProfile(@RequestParam String userId) {
        return zaloService.getProfile(userId);
    }

    @GetMapping("/zalo/messages/read")
    public Mono<String> readZaloMessages() {
        return zaloService.readMessages();
    }

    @PostMapping("/zalo/messages/send")
    public Mono<String> sendZaloMessage(@RequestParam String recipientId, @RequestParam String message) {
        return zaloService.sendMessage(recipientId, message);
    }

    @GetMapping("/zalo/callback")
    public Mono<String> zaloCallback(@RequestParam String code) {
        return zaloService.exchangeCodeForAccessToken(code)
                .thenReturn("Access token obtained successfully!")
                .onErrorResume(e -> Mono.just("Error obtaining access token: " + e.getMessage()));
    }

    @PostMapping("/zalo/gemini/command")
    public Mono<String> handleGeminiCommand(@RequestBody String command) {
        return zaloService.handleGeminiCommand(command);
    }
}