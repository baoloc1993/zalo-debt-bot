package io.github.baoloc1993.zalobot.controller;

import io.github.baoloc1993.zalobot.service.ZaloService;
import io.github.baoloc1993.zalobot.model.zalo.ZaloWebhookEvent;
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

    @GetMapping("/zalo/conversation")
    public Mono<String> getConversation(@RequestParam String userId, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "10") int count) {
        return zaloService.getConversation(userId, offset, count);
    }

    @GetMapping("/zalo/profile")
    public Mono<String> getProfile(@RequestParam String userId) {
        return zaloService.getProfile(userId);
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
                    String zaloMessageId = event.getMessage() != null ? event.getMessage().getMsgId() : null;
                    logger.info("Received Zalo message from senderId: {} with text: {} and msgId: {}", senderId, messageText, zaloMessageId);

                    if (messageText.isEmpty()) {
                        logger.warn("Received empty message from senderId: {}. Skipping processing.", senderId);
                        return Mono.just("ok");
                    }

                    logger.debug("Processing incoming Zalo message.");
                    return zaloService.processIncomingZaloMessage(senderId, messageText, zaloMessageId)
                            .thenReturn("ok");
                })
                .onErrorResume(e -> {
                    logger.error("Error processing Zalo webhook: {}", e.getMessage());
                    return Mono.just("error");
                });
    }
}