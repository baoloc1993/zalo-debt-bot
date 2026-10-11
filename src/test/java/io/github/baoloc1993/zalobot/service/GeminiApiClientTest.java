package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.gemini.GeminiMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GeminiApiClientTest {

    private MockWebServer mockWebServer;
    private GeminiApiClient geminiApiClient;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        String baseUrl = String.format("http://localhost:%s", mockWebServer.getPort());
        geminiApiClient = new GeminiApiClient(WebClient.builder(), "test-api-key", baseUrl);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
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

    @Test
    void generateContent_sendsApiKeyAsHeaderNotQueryParam() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setBody("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}")
                .addHeader("Content-Type", "application/json"));

        StepVerifier.create(geminiApiClient.generateContent(createTestHistory("test prompt")))
                .expectNext("ok")
                .verifyComplete();

        RecordedRequest request = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("test-api-key", request.getHeader("x-goog-api-key"));
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", request.getPath());
        assertFalse(request.getPath().contains("key="));
    }
}
