package com.example.zalobot.service;

import com.example.zalobot.model.DebtRecord;
import com.example.zalobot.repository.DebtRecordRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class ZaloService {

    private final ZaloConfig zaloConfig;
    private final WebClient webClient;
    private final GeminiApiClient geminiApiClient;
    private final ZaloMessageRepository zaloMessageRepository;
    private final DebtRecordRepository debtRecordRepository;
    private final Gson gson = new Gson();

    public ZaloService(ZaloConfig zaloConfig, WebClient.Builder webClientBuilder, GeminiApiClient geminiApiClient, ZaloMessageRepository zaloMessageRepository, DebtRecordRepository debtRecordRepository) {
        this.zaloConfig = zaloConfig;
        this.webClient = webClientBuilder.baseUrl("https://openapi.zalo.me/v2.0").build();
        this.geminiApiClient = geminiApiClient;
        this.zaloMessageRepository = zaloMessageRepository;
        this.debtRecordRepository = debtRecordRepository;
    }

    public Mono<String> handleGeminiCommand(String senderId, String command) {
        // For Gemini API, we still need conversation history in memory for context
        List<GeminiMessage> history = getConversationHistory(senderId);
        GeminiMessage userMessage = new GeminiMessage("user", List.of(new Part(command)));
        history.add(userMessage);

        return geminiApiClient.generateContent(history)
                .doOnNext(geminiResponseText -> {
                    // Add Gemini's response to history
                    GeminiMessage geminiMessage = new GeminiMessage("model", List.of(new Part(geminiResponseText)));
                    history.add(geminiMessage);

                    // Save Gemini's response to DB
                    ZaloMessage geminiZaloMessage = new ZaloMessage();
                    geminiZaloMessage.setSenderId("Gemini");
                    geminiZaloMessage.setReceiverId(senderId);
                    geminiZaloMessage.setMessageContent(geminiResponseText);
                    geminiZaloMessage.setMessageType("text");
                    geminiZaloMessage.setTimestamp(LocalDateTime.now());
                    geminiZaloMessage.setDirection(Direction.outbound);
                    geminiZaloMessage.setRawData(gson.toJson(Map.of("response", geminiResponseText)));
                    zaloMessageRepository.save(geminiZaloMessage);
                });
    }

    public Mono<DebtAnalysisResult> analyzeMessageForDebt(String messageContent) {
        List<GeminiMessage> prompt = List.of(
                new GeminiMessage("user", List.of(new Part("Bạn là một trợ lý phân tích tin nhắn. Hãy kiểm tra xem tin nhắn sau có đề cập đến việc ai đó nợ tiền hay không. Nếu có, hãy trích xuất các thông tin sau: tên người nợ, số tiền nợ, lý do nợ, và ngày nợ (nếu có). Trả về kết quả dưới dạng JSON với các trường: 'is_debt_related' (boolean), 'debtor_name' (string), 'amount' (string), 'reason' (string), 'due_date' (string, định dạng YYYY-MM-DD). Nếu không có thông tin, hãy để giá trị là null hoặc chuỗi rỗng. Tin nhắn: " + messageContent)))
        );
        return geminiApiClient.generateContent(prompt)
                .map(geminiResponse -> {
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(geminiResponse).getAsJsonObject();
                        boolean isDebtRelated = jsonResponse.has("is_debt_related") && jsonResponse.get("is_debt_related").getAsBoolean();
                        return new DebtAnalysisResult(isDebtRelated, geminiResponse);
                    } catch (Exception e) {
                        // Log error and return a default non-debt related result
                        System.err.println("Error parsing Gemini debt analysis response: " + e.getMessage());
                        return new DebtAnalysisResult(false, geminiResponse);
                    }
                });
    }

    public Mono<DebtAnalysisResult> processIncomingZaloMessage(String senderId, String messageText) {
        // Save user message to DB
        ZaloMessage userZaloMessage = new ZaloMessage();
        userZaloMessage.setSenderId(senderId);
        userZaloMessage.setReceiverId("OA"); // Assuming OA is the receiver for incoming messages
        userZaloMessage.setMessageContent(messageText);
        userZaloMessage.setMessageType("text");
        userZaloMessage.setTimestamp(LocalDateTime.now());
        userZaloMessage.setDirection(Direction.inbound);
        userZaloMessage.setRawData(gson.toJson(Map.of("original_message", messageText)));
        ZaloMessage savedMessage = zaloMessageRepository.save(userZaloMessage);

        return analyzeMessageForDebt(messageText)
                .doOnNext(debtAnalysisResult -> {
                    // Update the saved message with debt analysis result
                    savedMessage.setRawData(debtAnalysisResult.getAnalysisJson());
                    zaloMessageRepository.save(savedMessage);

                    if (debtAnalysisResult.isDebtRelated()) {
                        try {
                            JsonObject jsonResponse = JsonParser.parseString(debtAnalysisResult.getAnalysisJson()).getAsJsonObject();
                            String debtorName = jsonResponse.has("debtor_name") && !jsonResponse.get("debtor_name").isJsonNull() ? jsonResponse.get("debtor_name").getAsString() : null;
                            String amount = jsonResponse.has("amount") && !jsonResponse.get("amount").isJsonNull() ? jsonResponse.get("amount").getAsString() : null;
                            String reason = jsonResponse.has("reason") && !jsonResponse.get("reason").isJsonNull() ? jsonResponse.get("reason").getAsString() : null;
                            String dueDateStr = jsonResponse.has("due_date") && !jsonResponse.get("due_date").isJsonNull() ? jsonResponse.get("due_date").getAsString() : null;

                            LocalDate dueDate = null;
                            if (dueDateStr != null && !dueDateStr.isEmpty()) {
                                try {
                                    dueDate = LocalDate.parse(dueDateStr);
                                } catch (DateTimeParseException e) {
                                    System.err.println("Error parsing due_date: " + dueDateStr + ". " + e.getMessage());
                                }
                            }

                            DebtRecord debtRecord = new DebtRecord();
                            debtRecord.setZaloMessageId(savedMessage.getId());
                            debtRecord.setDebtorName(debtorName);
                            debtRecord.setAmount(amount);
                            debtRecord.setReason(reason);
                            debtRecord.setDueDate(dueDate);
                            debtRecordRepository.save(debtRecord);
                            System.out.println("Saved new DebtRecord: " + debtRecord);
                        } catch (JsonSyntaxException e) {
                            System.err.println("Error parsing debt analysis JSON for DebtRecord: " + e.getMessage());
                        }
                    }
                });
    }

    // Helper to manage in-memory history for Gemini API context
    private final Map<String, List<GeminiMessage>> inMemoryConversationHistory = new ConcurrentHashMap<>();

    private List<GeminiMessage> getConversationHistory(String senderId) {
        return inMemoryConversationHistory.computeIfAbsent(senderId, k -> new CopyOnWriteArrayList<>());
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