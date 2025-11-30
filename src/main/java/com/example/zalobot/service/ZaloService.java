package com.example.zalobot.service;

import java.math.BigDecimal;
import com.example.zalobot.model.AppUser;
import com.example.zalobot.model.DebtRecord;
import com.example.zalobot.model.gemini.GeminiMessage;
import com.example.zalobot.model.PaymentRecord;
import com.example.zalobot.model.TransactionAnalysisResult;
import com.example.zalobot.model.ZaloMessage;
import com.example.zalobot.repository.AppUserRepository;
import com.example.zalobot.repository.DebtRecordRepository;
import com.example.zalobot.repository.PaymentRecordRepository;
import com.example.zalobot.repository.ZaloMessageRepository;
import com.example.zalobot.config.ZaloConfig;
import com.example.zalobot.service.GeminiApiClient;
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
    private final AppUserRepository appUserRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final Gson gson = new Gson();

    public ZaloService(ZaloConfig zaloConfig, WebClient.Builder webClientBuilder, GeminiApiClient geminiApiClient, ZaloMessageRepository zaloMessageRepository, DebtRecordRepository debtRecordRepository, AppUserRepository appUserRepository, PaymentRecordRepository paymentRecordRepository) {
        this.zaloConfig = zaloConfig;
        this.webClient = webClientBuilder.baseUrl("https://openapi.zalo.me/v2.0").build();
        this.geminiApiClient = geminiApiClient;
        this.zaloMessageRepository = zaloMessageRepository;
        this.debtRecordRepository = debtRecordRepository;
        this.appUserRepository = appUserRepository;
        this.paymentRecordRepository = paymentRecordRepository;
    }

    public Mono<String> handleGeminiCommand(String senderId, String command) {
        // For Gemini API, we still need conversation history in memory for context
        List<GeminiMessage> history = getConversationHistory(senderId);
        GeminiMessage userMessage = new GeminiMessage("user", List.of(new GeminiMessage.Part(command)));
        history.add(userMessage);

        return geminiApiClient.generateContent(history)
                .doOnNext(geminiResponseText -> {
                    // Add Gemini's response to history
                    GeminiMessage geminiMessage = new GeminiMessage("model", List.of(new GeminiMessage.Part(geminiResponseText)));
                    history.add(geminiMessage);

                    // Save Gemini's response to DB
                    ZaloMessage geminiZaloMessage = new ZaloMessage();
                    geminiZaloMessage.setSenderId("Gemini");
                    geminiZaloMessage.setReceiverId(senderId);
                    geminiZaloMessage.setMessageContent(geminiResponseText);
                    geminiZaloMessage.setMessageType("text");
                    geminiZaloMessage.setTimestamp(LocalDateTime.now());
                    geminiZaloMessage.setDirection(ZaloMessage.Direction.outbound);
                    geminiZaloMessage.setRawData(gson.toJson(Map.of("response", geminiResponseText)));
                    zaloMessageRepository.save(geminiZaloMessage);
                });
    }

    public Mono<TransactionAnalysisResult> analyzeMessageForDebt(String messageContent) {
        List<GeminiMessage> prompt = List.of(
                new GeminiMessage("user", List.of(new GeminiMessage.Part("Bạn là một trợ lý phân tích tin nhắn. Hãy kiểm tra xem tin nhắn sau có đề cập đến giao dịch nợ hoặc thanh toán hay không, hoặc có yêu cầu tổng hợp nợ/thanh toán hay không. Nếu có giao dịch, hãy trích xuất các thông tin sau: tên người liên quan (người nợ hoặc người trả), số tiền giao dịch, lý do giao dịch, và ngày giao dịch (nếu có). Nếu có yêu cầu tổng hợp, hãy xác định loại tổng hợp (nợ, thanh toán, hoặc tất cả). Trả về kết quả dưới dạng JSON với các trường: 'is_transaction_related' (boolean), 'transaction_type' (string, 'debt' hoặc 'payment'), 'app_user_id' (long, ID của người dùng trong hệ thống, nếu có), 'app_user_name' (string, tên người dùng), 'amount' (number), 'reason' (string), 'transaction_date' (string, định dạng YYYY-MM-DD), 'is_summary_request' (boolean), 'summary_type' (string, 'debt', 'payment', 'all'). Nếu không có thông tin, hãy để giá trị là null hoặc chuỗi rỗng. Tin nhắn: " + messageContent))));
        return geminiApiClient.generateContent(prompt)
                .map(geminiResponse -> {
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(geminiResponse).getAsJsonObject();
                        boolean isTransactionRelated = jsonResponse.has("is_transaction_related") && jsonResponse.get("is_transaction_related").getAsBoolean();
                        String transactionType = jsonResponse.has("transaction_type") && !jsonResponse.get("transaction_type").isJsonNull() ? jsonResponse.get("transaction_type").getAsString() : null;
                        boolean isSummaryRequest = jsonResponse.has("is_summary_request") && jsonResponse.get("is_summary_request").getAsBoolean();
                        String summaryType = jsonResponse.has("summary_type") && !jsonResponse.get("summary_type").isJsonNull() ? jsonResponse.get("summary_type").getAsString() : null;
                        return new TransactionAnalysisResult(transactionType, isTransactionRelated, isSummaryRequest, summaryType, geminiResponse);
                    } catch (Exception e) {
                        // Log error and return a default non-transaction related result
                        System.err.println("Error parsing Gemini transaction analysis response: " + e.getMessage());
                        return new TransactionAnalysisResult(null, false, false, null, geminiResponse);
                    }
                });
    }

    public Mono<TransactionAnalysisResult> processIncomingZaloMessage(String senderId, String messageText) {
        // Save user message to DB
        ZaloMessage userZaloMessage = new ZaloMessage();
        userZaloMessage.setSenderId(senderId);
        userZaloMessage.setReceiverId("OA"); // Assuming OA is the receiver for incoming messages
        userZaloMessage.setMessageContent(messageText);
        userZaloMessage.setMessageType("text");
        userZaloMessage.setTimestamp(LocalDateTime.now());
        userZaloMessage.setDirection(ZaloMessage.Direction.inbound);
        userZaloMessage.setRawData(gson.toJson(Map.of("original_message", messageText)));
        ZaloMessage savedMessage = zaloMessageRepository.save(userZaloMessage);

        return analyzeMessageForDebt(messageText)
                .doOnNext(transactionAnalysisResult -> {
                    // Update the saved message with debt analysis result
                    savedMessage.setRawData(transactionAnalysisResult.getAnalysisJson());
                    zaloMessageRepository.save(savedMessage);

                    if (transactionAnalysisResult.isSummaryRequest()) {
                        JsonObject jsonResponse = JsonParser.parseString(transactionAnalysisResult.getAnalysisJson()).getAsJsonObject();
                        String appUserName = jsonResponse.has("app_user_name") && !jsonResponse.get("app_user_name").isJsonNull() ? jsonResponse.get("app_user_name").getAsString() : null;
                        Long appUserId = jsonResponse.has("app_user_id") && !jsonResponse.get("app_user_id").isJsonNull() ? jsonResponse.get("app_user_id").getAsLong() : null;

                        AppUser appUser = null;
                        if (appUserId != null) {
                            appUser = appUserRepository.findById(appUserId).orElse(null);
                        }

                        if (appUser == null && appUserName != null && !appUserName.trim().isEmpty()) {
                            appUser = appUserRepository.findByName(appUserName)
                                    .orElseGet(() -> {
                                        AppUser newAppUser = new AppUser(null, appUserName);
                                        return appUserRepository.save(newAppUser);
                                    });
                        }

                        if (appUser == null) {
                            System.err.println("AppUser not found and cannot be created for summary request.");
                            sendMessage(senderId, "Không tìm thấy người dùng để tổng hợp. Vui lòng cung cấp tên hoặc ID người dùng hợp lệ.").subscribe();
                            return;
                        }

                        getSummaryForAppUser(appUser, transactionAnalysisResult.getSummaryType())
                                .flatMap(summaryMessage -> sendMessage(senderId, summaryMessage))
                                .subscribe(
                                        response -> System.out.println("Summary message sent: " + response),
                                        error -> System.err.println("Failed to send summary message: " + error.getMessage())
                                );
                    } else if (transactionAnalysisResult.isTransactionRelated()) {
                        try {
                            JsonObject jsonResponse = JsonParser.parseString(transactionAnalysisResult.getAnalysisJson()).getAsJsonObject();
                            String appUserName = jsonResponse.has("app_user_name") && !jsonResponse.get("app_user_name").isJsonNull() ? jsonResponse.get("app_user_name").getAsString() : null;
                            Long appUserId = jsonResponse.has("app_user_id") && !jsonResponse.get("app_user_id").isJsonNull() ? jsonResponse.get("app_user_id").getAsLong() : null;

                            AppUser appUser = null;
                            if (appUserId != null) {
                                appUser = appUserRepository.findById(appUserId).orElse(null);
                            }

                            if (appUser == null && appUserName != null && !appUserName.trim().isEmpty()) {
                                appUser = appUserRepository.findByName(appUserName)
                                        .orElseGet(() -> {
                                            AppUser newAppUser = new AppUser(null, appUserName);
                                            return appUserRepository.save(newAppUser);
                                        });
                            }

                            if (appUser == null) {
                                System.err.println("AppUser not found and cannot be created, skipping transaction record.");
                                return;
                            }

                            BigDecimal amount = null;
                            if (jsonResponse.has("amount") && !jsonResponse.get("amount").isJsonNull()) {
                                try {
                                    amount = new BigDecimal(jsonResponse.get("amount").getAsString());
                                } catch (NumberFormatException e) {
                                    System.err.println("Error parsing amount to BigDecimal: " + jsonResponse.get("amount").getAsString() + ". " + e.getMessage());
                                }
                            }

                            String reason = jsonResponse.has("reason") && !jsonResponse.get("reason").isJsonNull() ? jsonResponse.get("reason").getAsString() : null;

                            if ("debt".equals(transactionAnalysisResult.getTransactionType())) {
                                String dueDateStr = jsonResponse.has("transaction_date") && !jsonResponse.get("transaction_date").isJsonNull() ? jsonResponse.get("transaction_date").getAsString() : null;

                                LocalDate dueDate = null;
                                if (dueDateStr != null && !dueDateStr.isEmpty()) {
                                    try {
                                        dueDate = LocalDate.parse(dueDateStr);
                                    } catch (DateTimeParseException e) {
                                        System.err.println("Error parsing transaction_date for DebtRecord: " + dueDateStr + ". " + e.getMessage());
                                    }
                                }

                                DebtRecord debtRecord = new DebtRecord();
                                debtRecord.setZaloMessageId(savedMessage.getId());
                                debtRecord.setAppUser(appUser);
                                debtRecord.setAmount(amount);
                                debtRecord.setReason(reason);
                                debtRecord.setDueDate(dueDate);
                                debtRecordRepository.save(debtRecord);
                                System.out.println("Saved new DebtRecord: " + debtRecord);
                            } else if ("payment".equals(transactionAnalysisResult.getTransactionType())) {
                                PaymentRecord paymentRecord = new PaymentRecord();
                                paymentRecord.setZaloMessageId(savedMessage.getId());
                                paymentRecord.setAppUser(appUser);
                                paymentRecord.setAmount(amount);
                                paymentRecord.setReason(reason);
                                paymentRecordRepository.save(paymentRecord);
                                System.out.println("Saved new PaymentRecord: " + paymentRecord);
                            } else {
                                System.out.println("Unknown transaction type: " + transactionAnalysisResult.getTransactionType());
                            }

                        } catch (JsonSyntaxException e) {
                            System.err.println("Error parsing transaction analysis JSON: " + e.getMessage());
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

    private Mono<String> getSummaryForAppUser(AppUser appUser, String summaryType) {
        Mono<BigDecimal> totalDebtMono = Mono.just(debtRecordRepository.sumAmountByAppUser(appUser));
        Mono<BigDecimal> totalPaymentMono = Mono.just(paymentRecordRepository.sumAmountByAppUser(appUser));

        return Mono.zip(totalDebtMono, totalPaymentMono)
                .map(tuple -> {
                    BigDecimal totalDebt = tuple.getT1();
                    BigDecimal totalPayment = tuple.getT2();
                    BigDecimal netDebt = totalDebt.subtract(totalPayment);

                    StringBuilder summary = new StringBuilder();
                    summary.append("Tổng hợp giao dịch cho ").append(appUser.getName()).append(":\n");

                    if ("debt".equals(summaryType) || "all".equals(summaryType)) {
                        summary.append("- Tổng nợ: ").append(totalDebt).append(" VND\n");
                    }
                    if ("payment".equals(summaryType) || "all".equals(summaryType)) {
                        summary.append("- Tổng thanh toán: ").append(totalPayment).append(" VND\n");
                    }
                    if ("all".equals(summaryType)) {
                        summary.append("- Nợ ròng: ").append(netDebt).append(" VND\n");
                    }
                    return summary.toString();
                });
    }
}