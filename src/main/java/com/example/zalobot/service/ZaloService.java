package com.example.zalobot.service;

import com.example.zalobot.config.ZaloConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ZaloService {

    private final ZaloConfig zaloConfig;
    private final WebClient webClient;

    public ZaloService(ZaloConfig zaloConfig, WebClient.Builder webClientBuilder) {
        this.zaloConfig = zaloConfig;
        this.webClient = webClientBuilder.baseUrl("https://openapi.zalo.me/v2.0").build();
    }

    public Mono<String> exchangeCodeForAccessToken(String code) {
        return WebClient.builder().baseUrl("https://oauth.zalo.me/v4").build().post()
                .uri("/access_token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("app_id=" + zaloConfig.getAppId() +
                        "&app_secret=" + zaloConfig.getAppSecret() +
                        "&code=" + code +
                        "&grant_type=authorization_code")
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(response -> {
                    System.out.println("Zalo Token Endpoint Response: " + response);
                    JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                    if (jsonResponse.has("error")) {
                        String errorDescription = jsonResponse.get("error_description").getAsString();
                        System.err.println("Error from Zalo token endpoint: " + errorDescription);
                        throw new RuntimeException("Error obtaining access token: " + errorDescription);
                    }
                    String accessToken = jsonResponse.get("access_token").getAsString();
                    String refreshToken = jsonResponse.get("refresh_token").getAsString();
                    zaloConfig.setAccessToken(accessToken);
                    zaloConfig.setRefreshToken(refreshToken);
                    System.out.println("Access Token: " + accessToken);
                    System.out.println("Refresh Token: " + refreshToken);
                });
    }

    public Mono<String> getProfile(String userId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/me")
                        .queryParam("access_token", zaloConfig.getAccessToken())
                        .queryParam("user_id", userId)
                        .build())
                .retrieve()
                .bodyToMono(String.class);
    }

    public Mono<String> readMessages() {
        System.out.println("Reading messages from Zalo API...");
        // TODO: Implement actual Zalo API call to read messages
        return Mono.just("Messages read successfully (placeholder)");
    }

    public Mono<String> sendMessage(String recipientId, String message) {
        JsonObject recipient = new JsonObject();
        recipient.addProperty("user_id", recipientId);

        JsonObject messageBody = new JsonObject();
        messageBody.addProperty("text", message);

        JsonObject requestBody = new JsonObject();
        requestBody.add("recipient", recipient);
        requestBody.add("message", messageBody);

        return webClient.post()
                .uri(uriBuilder -> uriBuilder.path("/oa/message").queryParam("access_token", zaloConfig.getAccessToken()).build())
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class);
    }
}