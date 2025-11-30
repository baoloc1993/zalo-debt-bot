package com.example.zalobot.controller;

import com.example.zalobot.service.ZaloService;
import com.example.zalobot.model.zalo.ZaloWebhookEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
public class ZaloController {

    private static final Logger logger = LoggerFactory.getLogger(ZaloController.class);

    private final ZaloService zaloService;
    private final ObjectMapper objectMapper;

    public ZaloController(ZaloService zaloService, ObjectMapper objectMapper) {
        this.zaloService = zaloService;
        this.objectMapper = objectMapper;
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
    public Mono<String> handleGeminiCommand(@RequestParam String senderId, @RequestBody String command) {
        return zaloService.handleGeminiCommand(senderId, command);
    }

    @GetMapping("/zalo/webhook")
    public Mono<String> verifyZaloWebhook(@RequestParam("challenge") String challenge) {
        logger.info("Received Zalo webhook verification challenge: {}", challenge);
        return Mono.just(challenge);
    }

    @PostMapping("/zalo/webhook")
    public Mono<String> handleZaloWebhook(@RequestBody String payload) {
        logger.info("Received Zalo webhook payload: {}", payload);
        return Mono.fromCallable(() -> {
                    logger.debug("Attempting to parse Zalo webhook payload.");
                    return objectMapper.readValue(payload, ZaloWebhookEvent.class);
                })
                .flatMap(event -> {
                    logger.debug("Payload parsed successfully. Event name: {}", event.getEventName());
                    String senderId = event.getSenderId();
                    String messageText = event.getMessage() != null ? event.getMessage().getText() : "";
                    logger.info("Received Zalo message from senderId: {} with text: {}", senderId, messageText);

                    if (messageText.isEmpty()) {
                        logger.warn("Received empty message from senderId: {}. Skipping Gemini processing.", senderId);
                        return Mono.just("ok");
                    }

                    logger.debug("Processing incoming Zalo message for debt analysis.");
                    return zaloService.processIncomingZaloMessage(senderId, messageText)
                            .flatMap(debtAnalysisResult -> {
                                logger.info("Debt analysis result for senderId {}: isTransactionRelated={}, rawData={}", senderId, debtAnalysisResult.isTransactionRelated(), debtAnalysisResult.getAnalysisJson());
                                if (debtAnalysisResult.isTransactionRelated()) {
                                    String debtResponse = "Tin nhắn này có vẻ liên quan đến việc nợ tiền. Tôi đã ghi nhận thông tin chi tiết.";
                                    return zaloService.sendMessage(senderId, debtResponse)
                                            .doOnSuccess(response -> logger.info("Successfully sent debt-related message to Zalo: {}", response))
                                            .doOnError(e -> logger.error("Error sending debt-related message to Zalo for senderId: {}: {}", senderId, e.getMessage()))
                                            .thenReturn("ok");
                                } else {
                                    logger.debug("Message not debt-related. Sending to Gemini API for general command.");
                                    return zaloService.handleGeminiCommand(senderId, messageText)
                                            .flatMap(geminiResponse -> {
                                                logger.info("Received response from Gemini for general command: {}", geminiResponse);
                                                logger.debug("Sending Gemini response back to Zalo senderId: {}", senderId);
                                                return zaloService.sendMessage(senderId, geminiResponse)
                                                        .doOnSuccess(response -> logger.info("Successfully sent message to Zalo: {}", response))
                                                        .doOnError(e -> logger.error("Error sending message to Zalo for senderId: {}: {}", senderId, e.getMessage()))
                                                        .thenReturn("ok");
                                            })
                                            .doOnError(e -> logger.error("Error during general Gemini API call for senderId: {}: {}", senderId, e.getMessage()))
                                            .thenReturn("ok");
                                }
                            })
                            .doOnError(e -> logger.error("Error during processing incoming Zalo message for senderId: {}: {}", senderId, e.getMessage()))
                            .thenReturn("ok");
                })
                .onErrorResume(e -> {
                    logger.error("Error processing Zalo webhook: {}", e.getMessage());
                    return Mono.just("error");
                });
    }
}