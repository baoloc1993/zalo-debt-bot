package io.github.baoloc1993.zalobot.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // --- analyzeMessageForDebt ---

    /** Enqueues a Gemini generateContent response whose single candidate contains the given text. */
    private void enqueueModelAnswer(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonObject candidate = new JsonObject();
        candidate.add("content", content);
        JsonArray candidates = new JsonArray();
        candidates.add(candidate);
        JsonObject body = new JsonObject();
        body.add("candidates", candidates);
        mockWebServer.enqueue(new MockResponse()
                .setBody(body.toString())
                .addHeader("Content-Type", "application/json"));
    }

    private static final String DEBT_JSON = "{\"is_transaction_related\": true, \"transaction_type\": \"debt\","
            + " \"debt_direction\": \"USER_OWES_OTHER\", \"app_user_name\": \"Lan\", \"amount\": 50000,"
            + " \"reason\": \"tiền ăn trưa\", \"transaction_date\": \"2025-11-30\","
            + " \"is_summary_request\": false, \"summary_type\": null}";

    @Test
    void analyzeMessageForDebt_extractsDebtDetails() throws InterruptedException {
        enqueueModelAnswer(DEBT_JSON);

        StepVerifier.create(geminiApiClient.analyzeMessageForDebt("Tôi nợ Lan 50k tiền ăn trưa"))
                .assertNext(r -> {
                    assertTrue(r.isTransactionRelated());
                    assertEquals("debt", r.getTransactionType());
                    assertEquals("USER_OWES_OTHER", r.getDebtDirection());
                    assertEquals("Lan", r.getAppUserName());
                    assertEquals(50000.0, r.getAmount());
                    assertEquals("tiền ăn trưa", r.getReason());
                    assertEquals("2025-11-30", r.getTransactionDate());
                    assertFalse(r.isSummaryRequest());
                })
                .verifyComplete();

        RecordedRequest request = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getBody().readUtf8().contains("Tôi nợ Lan 50k tiền ăn trưa"));
    }

    @Test
    void analyzeMessageForDebt_handlesMarkdownFencedJson() {
        enqueueModelAnswer("```json\n" + DEBT_JSON + "\n```");

        StepVerifier.create(geminiApiClient.analyzeMessageForDebt("Tôi nợ Lan 50k"))
                .assertNext(r -> {
                    assertTrue(r.isTransactionRelated());
                    assertEquals("Lan", r.getAppUserName());
                })
                .verifyComplete();
    }

    @Test
    void analyzeMessageForDebt_truncatedJsonYieldsEmptyResult() {
        enqueueModelAnswer("```json\n{\"is_transaction_related\": true, \"transaction_type\": \"de");

        StepVerifier.create(geminiApiClient.analyzeMessageForDebt("Tôi nợ Lan 50k"))
                .assertNext(r -> {
                    assertFalse(r.isTransactionRelated());
                    assertNull(r.getTransactionType());
                    assertNull(r.getAmount());
                })
                .verifyComplete();
    }

    @Test
    void analyzeMessageForDebt_invalidJsonYieldsEmptyResult() {
        enqueueModelAnswer("{\"is_transaction_related\": true, \"amount\": }");

        StepVerifier.create(geminiApiClient.analyzeMessageForDebt("hello"))
                .assertNext(r -> assertFalse(r.isTransactionRelated()))
                .verifyComplete();
    }

    @Test
    void analyzeMessageForDebt_apiErrorYieldsEmptyResult() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":{}}"));

        StepVerifier.create(geminiApiClient.analyzeMessageForDebt("hello"))
                .assertNext(r -> assertFalse(r.isTransactionRelated()))
                .verifyComplete();
    }
}
