package com.example.zalobot.service;

import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import com.example.zalobot.model.gemini.GeminiMessage;
import com.example.zalobot.model.gemini.GeminiRequest;
import com.example.zalobot.model.gemini.GeminiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class GeminiApiClient {

    private static final Logger logger = LoggerFactory.getLogger(GeminiApiClient.class);

    private final WebClient webClient;
    private final String geminiApiKey;
    private final String geminiApiUrl;

    public GeminiApiClient(WebClient.Builder webClientBuilder,
                           @Value("${gemini.api.key}") String geminiApiKey,
                           @Value("${gemini.api.url}") String geminiApiUrl) {
        this.webClient = webClientBuilder.baseUrl(geminiApiUrl).build();
        this.geminiApiKey = geminiApiKey;
        this.geminiApiUrl = geminiApiUrl;
    }

    public Mono<com.example.zalobot.model.TransactionAnalysisResult> analyzeMessageForDebt(String message) {
        // This is a placeholder implementation.
        // In a real application, you would call the Gemini API here.
        return Mono.just(new com.example.zalobot.model.TransactionAnalysisResult());
    }

    public Mono<String> generateContent(List<GeminiMessage> messages) {
        GeminiRequest requestBody = new GeminiRequest(messages);

        logger.info("Sending request to Gemini API with messages: {}", messages);
        logger.debug("Gemini API Request Body: {}", requestBody);

        return webClient.post()
                .uri(uriBuilder -> uriBuilder.path("/v1beta/models/gemini-2.5-flash:generateContent").queryParam("key", geminiApiKey).build())
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class).flatMap(errorBody -> {
                    logger.error("Gemini API Error: Status - {}, Body - {}", response.statusCode(), errorBody);
                    return Mono.error(new RuntimeException("Gemini API error: " + errorBody));
                }))
                .bodyToMono(GeminiResponse.class)
                .map(geminiResponse -> {
                    if (geminiResponse != null && geminiResponse.getCandidates() != null && !geminiResponse.getCandidates().isEmpty()) {
                        String generatedText = geminiResponse.getCandidates().get(0).getContent().getParts().get(0).getText();
                        logger.info("Received response from Gemini API: {}", generatedText);
                        return generatedText;
                    }
                    logger.warn("No content candidates found in Gemini API response.");
                    return "No response from Gemini API.";
                })
                .onErrorResume(e -> {
                    logger.error("Error during Gemini API call: {}", e.getMessage());
                    return Mono.just("Error communicating with Gemini API: " + e.getMessage());
                });
    }
}
