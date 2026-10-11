package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.gemini.GeminiMessage;
import io.github.baoloc1993.zalobot.model.gemini.GeminiResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GeminiApiClientTest {

    public static MockWebServer mockWebServer;
    private GeminiApiClient geminiApiClient;

    @BeforeAll
    static void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @BeforeEach
    void initialize() {
        String baseUrl = String.format("http://localhost:%s", mockWebServer.getPort());
        geminiApiClient = new GeminiApiClient(WebClient.builder(), "test-api-key", baseUrl);
    }

    private List<GeminiMessage> createTestHistory(String prompt) {
        return List.of(new GeminiMessage("user", List.of(new GeminiMessage.Part(prompt))));
    }

    @Test
    void generateContent_success() {
        String mockResponse = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello from Gemini!\"}]}}]}";
        mockWebServer.enqueue(new MockResponse()
                .setBody(mockResponse)
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(geminiApiClient.generateContent(createTestHistory("test prompt")))
                .expectNext("Hello from Gemini!")
                .verifyComplete();
    }

    @Test
    void generateContent_emptyResponse() {
        String mockResponse = "{\"candidates\":[]}";
        mockWebServer.enqueue(new MockResponse()
                .setBody(mockResponse)
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(geminiApiClient.generateContent(createTestHistory("test prompt")))
                .expectNext("No response from Gemini API.")
                .verifyComplete();
    }

    @Test
    void generateContent_apiError() {
        String errorResponse = "{\"error\":{\"message\":\"API error\"}}";
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody(errorResponse)
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(geminiApiClient.generateContent(createTestHistory("test prompt")))
                .expectNextMatches(s -> s.contains("Error communicating with Gemini API"))
                .verifyComplete();
    }
}
