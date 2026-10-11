package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.TransactionAnalysisResult;
import io.github.baoloc1993.zalobot.model.gemini.GeminiMessage;
import io.github.baoloc1993.zalobot.model.gemini.GeminiRequest;
import io.github.baoloc1993.zalobot.model.gemini.GeminiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Thin reactive client for the Google Gemini {@code generateContent} REST endpoint.
 */
@Service
public class GeminiApiClient {

    private static final Logger logger = LoggerFactory.getLogger(GeminiApiClient.class);

    private static final String GENERATE_CONTENT_PATH = "/v1beta/models/gemini-2.5-flash:generateContent";

    private final WebClient webClient;
    private final String geminiApiKey;

    public GeminiApiClient(WebClient.Builder webClientBuilder,
                           @Value("${gemini.api.key}") String geminiApiKey,
                           @Value("${gemini.api.url}") String geminiApiUrl) {
        this.webClient = webClientBuilder.baseUrl(geminiApiUrl).build();
        this.geminiApiKey = geminiApiKey;
    }

    /**
     * Classifies a chat message as a debt/payment transaction.
     * <p>
     * TODO: not implemented yet - currently returns an empty result, which the
     * caller treats as "not transaction related".
     */
    public Mono<TransactionAnalysisResult> analyzeMessageForDebt(String message) {
        return Mono.just(new TransactionAnalysisResult());
    }

    public Mono<String> generateContent(List<GeminiMessage> messages) {
        GeminiRequest requestBody = new GeminiRequest(messages);

        logger.info("Sending request to Gemini API with {} message(s).", messages.size());

        return webClient.post()
                .uri(GENERATE_CONTENT_PATH)
                // Send the key as a header rather than a query parameter so it never appears in URLs or logs
                .header("x-goog-api-key", geminiApiKey)
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
                        logger.debug("Received response from Gemini API: {}", generatedText);
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
