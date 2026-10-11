package io.github.baoloc1993.zalobot.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    private static final String DEBT_ANALYSIS_PROMPT = """
            Bạn là một trợ lý phân tích tin nhắn. Hãy kiểm tra xem tin nhắn sau có đề cập đến giao dịch nợ hoặc             thanh toán hay không, hoặc có yêu cầu tổng hợp nợ/thanh toán hay không.
            Nếu có giao dịch, hãy trích xuất: tên người liên quan (người nợ hoặc người trả), số tiền, lý do và             ngày giao dịch (nếu có).
            Xác định hướng của khoản nợ: nếu người gửi tin nhắn nợ người khác thì 'debt_direction' là             'USER_OWES_OTHER'; nếu người khác nợ người gửi thì 'debt_direction' là 'OTHER_OWES_USER'.
            Nếu có yêu cầu tổng hợp, hãy xác định loại tổng hợp (nợ, thanh toán, hoặc tất cả).
            Chỉ trả về một đối tượng JSON, không kèm giải thích, với các trường:
            'is_transaction_related' (boolean), 'transaction_type' ('debt' hoặc 'payment'),             'debt_direction' ('USER_OWES_OTHER' hoặc 'OTHER_OWES_USER'), 'app_user_name' (tên người liên quan),             'amount' (số, đơn vị đồng), 'reason' (string), 'transaction_date' (YYYY-MM-DD),             'is_summary_request' (boolean), 'summary_type' ('debt', 'payment' hoặc 'all').
            Nếu không có thông tin, để giá trị là null.
            Ví dụ:
            Tin nhắn: 'Tôi nợ Lan 50k tiền ăn trưa.' -> {"is_transaction_related": true, "transaction_type": "debt",             "debt_direction": "USER_OWES_OTHER", "app_user_name": "Lan", "amount": 50000, "reason": "tiền ăn trưa",             "transaction_date": null, "is_summary_request": false, "summary_type": null}
            Tin nhắn: 'Lan nợ tôi 50k tiền ăn trưa.' -> {"is_transaction_related": true, "transaction_type": "debt",             "debt_direction": "OTHER_OWES_USER", "app_user_name": "Lan", "amount": 50000, "reason": "tiền ăn trưa",             "transaction_date": null, "is_summary_request": false, "summary_type": null}
            Tin nhắn: """;

    private final WebClient webClient;
    private final String geminiApiKey;

    public GeminiApiClient(WebClient.Builder webClientBuilder,
                           @Value("${gemini.api.key}") String geminiApiKey,
                           @Value("${gemini.api.url}") String geminiApiUrl) {
        this.webClient = webClientBuilder.baseUrl(geminiApiUrl).build();
        this.geminiApiKey = geminiApiKey;
    }

    /**
     * Asks Gemini whether a chat message describes a debt or payment and extracts its details.
     * <p>
     * Never fails: if the model answer is missing, not valid JSON or truncated, an empty
     * result (not transaction related) is returned and a warning is logged. The message
     * text itself is not logged.
     */
    public Mono<TransactionAnalysisResult> analyzeMessageForDebt(String message) {
        List<GeminiMessage> prompt = List.of(
                new GeminiMessage("user", List.of(new GeminiMessage.Part(DEBT_ANALYSIS_PROMPT + " " + message))));
        return generateContent(prompt).map(GeminiApiClient::parseAnalysis);
    }

    static TransactionAnalysisResult parseAnalysis(String modelAnswer) {
        TransactionAnalysisResult result = new TransactionAnalysisResult();
        String json = extractJsonObject(modelAnswer);
        if (json == null) {
            logger.warn("Gemini answer contains no JSON object; treating message as not transaction related.");
            return result;
        }
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            result.setTransactionRelated(getBoolean(obj, "is_transaction_related"));
            result.setTransactionType(getString(obj, "transaction_type"));
            result.setDebtDirection(getString(obj, "debt_direction"));
            result.setAppUserName(getString(obj, "app_user_name"));
            result.setReason(getString(obj, "reason"));
            result.setTransactionDate(getString(obj, "transaction_date"));
            result.setSummaryRequest(getBoolean(obj, "is_summary_request"));
            result.setSummaryType(getString(obj, "summary_type"));
            result.setAmount(getDouble(obj, "amount"));
            result.setAnalysisJson(json);
            return result;
        } catch (RuntimeException e) {
            logger.warn("Could not parse Gemini transaction analysis ({}); treating message as not transaction related.",
                    e.getClass().getSimpleName());
            return new TransactionAnalysisResult();
        }
    }

    /** Strips Markdown code fences and surrounding prose, returning the outermost {...} block, or null. */
    private static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        return (start >= 0 && end > start) ? cleaned.substring(start, end + 1) : null;
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        String value = e.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    private static boolean getBoolean(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static Double getDouble(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        try {
            return e.getAsDouble();
        } catch (NumberFormatException ex) {
            return null;
        }
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
