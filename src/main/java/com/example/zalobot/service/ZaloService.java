package com.example.zalobot.service;

import java.math.BigDecimal;
import com.example.zalobot.model.AppUser;
import com.example.zalobot.model.UserAlias;
import com.example.zalobot.model.DebtRecord;
import com.example.zalobot.model.gemini.GeminiMessage;
import com.example.zalobot.model.PaymentRecord;
import com.example.zalobot.model.PendingUserConfirmation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import com.example.zalobot.model.TransactionAnalysisResult;
import com.example.zalobot.model.ZaloMessage;
import com.example.zalobot.repository.AppUserRepository;
import com.example.zalobot.repository.DebtRecordRepository;
import com.example.zalobot.repository.PaymentRecordRepository;
import com.example.zalobot.repository.PendingUserConfirmationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.zalobot.repository.UserAliasRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import org.springframework.beans.factory.annotation.Value;

@Service
public class ZaloService {
    private static final Logger logger = LoggerFactory.getLogger(ZaloService.class);

    private final ZaloConfig zaloConfig;
    private final WebClient webClient;
    private final GeminiApiClient geminiApiClient;
    private final ZaloMessageRepository zaloMessageRepository;
    private final DebtRecordRepository debtRecordRepository;
    private final AppUserRepository appUserRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final EntityResolutionService entityResolutionService;
    private final PendingUserConfirmationRepository pendingUserConfirmationRepository;
    private final UserAliasRepository userAliasRepository;
    private final Gson gson = new Gson();

    public ZaloService(ZaloConfig zaloConfig, WebClient.Builder webClientBuilder, GeminiApiClient geminiApiClient, ZaloMessageRepository zaloMessageRepository, DebtRecordRepository debtRecordRepository, AppUserRepository appUserRepository, PaymentRecordRepository paymentRecordRepository, EntityResolutionService entityResolutionService, PendingUserConfirmationRepository pendingUserConfirmationRepository, UserAliasRepository userAliasRepository) {
        this.zaloConfig = zaloConfig;
        this.webClient = webClientBuilder.baseUrl("https://openapi.zalo.me/v2.0").build();
        this.geminiApiClient = geminiApiClient;
        this.zaloMessageRepository = zaloMessageRepository;
        this.debtRecordRepository = debtRecordRepository;
        this.appUserRepository = appUserRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.entityResolutionService = entityResolutionService;
        this.pendingUserConfirmationRepository = pendingUserConfirmationRepository;
        this.userAliasRepository = userAliasRepository;
    }

    public ZaloConfig getZaloConfig() {
        return zaloConfig;
    }

    public Mono<String> refreshAccessToken() {
        logger.info("ZaloService - Entering refreshAccessToken method.");
        String currentAccessToken = zaloConfig.getAccessToken();
        if (currentAccessToken != null && !currentAccessToken.isEmpty()) {
            logger.info("Access token already exists in ZaloConfig, skipping refresh.");
            return Mono.just("Access token already exists, refresh skipped.");
        }
        logger.info("Refreshing Zalo access token...");
        String refreshToken = zaloConfig.getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            logger.error("Zalo refresh token is not configured.");
            return Mono.error(new RuntimeException("Zalo refresh token is not configured."));
        }
        logger.info("ZaloService - Refresh token from ZaloConfig: {}", refreshToken != null ? refreshToken.substring(0, Math.min(refreshToken.length(), 10)) + "..." : "null");
        logger.info("ZaloService - App Secret from ZaloConfig: {}", zaloConfig.getAppSecret() != null ? zaloConfig.getAppSecret().substring(0, Math.min(zaloConfig.getAppSecret().length(), 10)) + "..." : "null");
        String requestBody = "app_id=" + zaloConfig.getAppId() +
                "&app_secret=" + zaloConfig.getAppSecret() +
                "&refresh_token=" + refreshToken +
                "&grant_type=refresh_token";
        logger.info("ZaloService - Refresh token request body: {}", requestBody);

        logger.info("ZaloService - Building WebClient for token refresh.");
        return WebClient.builder().baseUrl("https://oauth.zalo.me/v4").build().post()
                .uri("/access_token")
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .doOnEach(signal -> logger.info("ZaloService - WebClient signal after bodyToMono: {}", signal.getType()))
                .doOnSuccess(response -> {
                    logger.info("Zalo Token Refresh Endpoint Response: {}", response);
                    JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                    if (jsonResponse.has("error")) {
                        String errorDescription = jsonResponse.get("error_description").getAsString();
                        logger.error("Error from Zalo token refresh endpoint: {}", errorDescription);
                        throw new RuntimeException("Error refreshing access token: " + errorDescription);
                    }
                    String newAccessToken = jsonResponse.get("access_token").getAsString();
                    String newRefreshToken = jsonResponse.get("refresh_token").getAsString();
                    zaloConfig.setAccessToken(newAccessToken);
                    zaloConfig.setRefreshToken(newRefreshToken);
                    logger.info("New Access Token obtained.");
                })
                .doOnError(e -> logger.error("Error during Zalo token refresh: {}", e.getMessage()));
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
                new GeminiMessage("user", List.of(new GeminiMessage.Part("Bạn là một trợ lý phân tích tin nhắn. Hãy kiểm tra xem tin nhắn sau có đề cập đến giao dịch nợ hoặc thanh toán hay không, hoặc có yêu cầu tổng hợp nợ/thanh toán hay không. Nếu có giao dịch, hãy trích xuất các thông tin sau: tên người liên quan (người nợ hoặc người trả), số tiền giao dịch, lý do giao dịch, và ngày giao dịch (nếu có). Đồng thời, xác định hướng của khoản nợ: nếu người dùng hiện tại nợ người khác, hãy đặt 'debt_direction' là 'USER_OWES_OTHER'; nếu người khác nợ người dùng hiện tại, hãy đặt 'debt_direction' là 'OTHER_OWES_USER'. Nếu có yêu cầu tổng hợp, hãy xác định loại tổng hợp (nợ, thanh toán, hoặc tất cả). Trả về kết quả dưới dạng JSON với các trường: 'is_transaction_related' (boolean), 'transaction_type' (string, 'debt' hoặc 'payment'), 'debt_direction' (string, 'USER_OWES_OTHER' hoặc 'OTHER_OWES_USER'), 'app_user_id' (long, ID của người dùng trong hệ thống, nếu có), 'app_user_name' (string, tên người dùng), 'amount' (number), 'reason' (string), 'transaction_date' (string, định dạng YYYY-MM-DD), 'is_summary_request' (boolean), 'summary_type' (string, 'debt', 'payment', 'all'). Nếu không có thông tin, hãy để giá trị là null hoặc chuỗi rỗng. Ví dụ: Tin nhắn: 'Tôi nợ Lan 50k tiền ăn trưa.' -> {'is_transaction_related': true, 'transaction_type': 'debt', 'debt_direction': 'USER_OWES_OTHER', 'app_user_name': 'Lan', 'amount': 50000, 'reason': 'tiền ăn trưa', 'transaction_date': null, 'is_summary_request': false, 'summary_type': null}. Tin nhắn: 'Lan nợ tôi 50k tiền ăn trưa.' -> {'is_transaction_related': true, 'transaction_type': 'debt', 'debt_direction': 'OTHER_OWES_USER', 'app_user_name': 'Lan', 'amount': 50000, 'reason': 'tiền ăn trưa', 'transaction_date': null, 'is_summary_request': false, 'summary_type': null}. Tin nhắn: " + messageContent))));
        return geminiApiClient.generateContent(prompt)
                .map(geminiResponse -> {
                    String cleanedResponse = geminiResponse.replace("```json", "").replace("```", "").trim();
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(cleanedResponse).getAsJsonObject();
                        boolean isTransactionRelated = jsonResponse.has("is_transaction_related") && jsonResponse.get("is_transaction_related").getAsBoolean();
                        String transactionType = jsonResponse.has("transaction_type") && !jsonResponse.get("transaction_type").isJsonNull() ? jsonResponse.get("transaction_type").getAsString() : null;
                        String debtDirection = jsonResponse.has("debt_direction") && !jsonResponse.get("debt_direction").isJsonNull() ? jsonResponse.get("debt_direction").getAsString() : null;
                        boolean isSummaryRequest = jsonResponse.has("is_summary_request") && jsonResponse.get("is_summary_request").getAsBoolean();
                        String summaryType = jsonResponse.has("summary_type") && !jsonResponse.get("summary_type").isJsonNull() ? jsonResponse.get("summary_type").getAsString() : null;
                        return new TransactionAnalysisResult(transactionType, debtDirection, isTransactionRelated, isSummaryRequest, summaryType, cleanedResponse);
                    } catch (Exception e) {
                        logger.error("Error parsing Gemini transaction analysis response: {}", e.getMessage());
                        return new TransactionAnalysisResult(null, null, false, false, null, "{}");
                    }
                });
    }

    @Transactional
    public Mono<TransactionAnalysisResult> processIncomingZaloMessage(String senderId, String messageText) {
        logger.info("Entering processIncomingZaloMessage for senderId: {} with message: {}", senderId, messageText);

        // Ensure sender AppUser exists
        AppUser senderAppUser = appUserRepository.findByZaloId(senderId).orElseGet(() -> {
            AppUser newSenderAppUser = new AppUser();
            newSenderAppUser.setZaloId(senderId);
            newSenderAppUser.setName(senderId); // Default name to senderId, can be updated later
            return appUserRepository.save(newSenderAppUser);
        });
        logger.info("Sender AppUser ID: {} for Zalo ID: {}", senderAppUser.getId(), senderId);

        Optional<PendingUserConfirmation> pendingConfirmation = pendingUserConfirmationRepository
                .findBySenderIdAndStatus(senderId, PendingUserConfirmation.ConfirmationStatus.PENDING);

        if (pendingConfirmation.isPresent()) {
            handleConfirmationResponse(senderId, messageText);
            return Mono.empty();
        }



        // Save user message to DB
        ZaloMessage userZaloMessage = new ZaloMessage();
        userZaloMessage.setSenderId(senderId);
        userZaloMessage.setReceiverId("OA"); // Assuming OA is the receiver for incoming messages
        userZaloMessage.setMessageContent(messageText);
        userZaloMessage.setMessageType("text");
        userZaloMessage.setTimestamp(LocalDateTime.now());
        userZaloMessage.setDirection(ZaloMessage.Direction.inbound);
        String initialRawData = gson.toJson(Map.of("original_message", messageText));
        logger.info("Initial rawData for userZaloMessage: {}", initialRawData);
        userZaloMessage.setRawData(initialRawData);
        ZaloMessage savedMessage = zaloMessageRepository.save(userZaloMessage);
        logger.info("Saved userZaloMessage with ID: {}, rawData: {}", savedMessage.getId(), savedMessage.getRawData());

        return analyzeMessageForDebt(messageText)
                .doOnNext(transactionAnalysisResult -> {
                    // Update the saved message with debt analysis result
                    String analysisJson = transactionAnalysisResult.getAnalysisJson();
                    logger.info("Analysis JSON from Gemini: {}", analysisJson);
                    savedMessage.setRawData(analysisJson);
                    zaloMessageRepository.save(savedMessage);
                    logger.info("Updated savedMessage with analysisJson. New rawData: {}", savedMessage.getRawData());

                    logger.info("DEBUG: Entering doOnNext block after transaction analysis. isTransactionRelated: {}", transactionAnalysisResult.isTransactionRelated());
                    if (transactionAnalysisResult.isSummaryRequest()) {
                        JsonObject jsonResponse = JsonParser.parseString(transactionAnalysisResult.getAnalysisJson()).getAsJsonObject();
                        String appUserName = jsonResponse.has("app_user_name") && !jsonResponse.get("app_user_name").isJsonNull() ? jsonResponse.get("app_user_name").getAsString() : null;
                        Long appUserId = jsonResponse.has("app_user_id") && !jsonResponse.get("app_user_id").isJsonNull() ? jsonResponse.get("app_user_id").getAsLong() : null;

                        AppUser appUser = null;
                        if (appUserId != null) {
                            appUser = appUserRepository.findById(appUserId).orElse(null);
                        }

                        if (appUser == null && appUserName != null && !appUserName.trim().isEmpty()) {
                            appUser = entityResolutionService.resolveUser(appUserName).orElse(null);
                        }

                        if (appUser == null) {
                            if (appUserName == null || appUserName.trim().isEmpty()) {
                                logger.warn("appUserName is null or empty for summary request. Cannot trigger user confirmation flow.");
                                sendMessage(senderId, "Không thể xác định tên người dùng. Vui lòng cung cấp tên người dùng rõ ràng hơn.").subscribe();
                                return;
                            }
                            triggerUserConfirmationFlow(senderId, appUserName, messageText);
                            return;
                        }

                        getSummaryForAppUser(appUser, transactionAnalysisResult.getSummaryType())
                                .flatMap(summaryMessage -> sendMessage(senderId, summaryMessage))
                                .subscribe(
                                        response -> logger.info("Summary message sent: {}", response),
                                        error -> logger.error("Failed to send summary message: {}", error.getMessage())
                                );
                    } else if (transactionAnalysisResult.isTransactionRelated()) {
                        try {
                            JsonObject jsonResponse = JsonParser.parseString(transactionAnalysisResult.getAnalysisJson()).getAsJsonObject();
                            logger.info("DEBUG: Parsed JSON response from Gemini: {}", jsonResponse.toString());
                            String appUserName = jsonResponse.has("app_user_name") && !jsonResponse.get("app_user_name").isJsonNull() ? jsonResponse.get("app_user_name").getAsString() : null;
                            logger.info("DEBUG: Extracted appUserName from Gemini response: {}", appUserName);
                            Long appUserId = jsonResponse.has("app_user_id") && !jsonResponse.get("app_user_id").isJsonNull() ? jsonResponse.get("app_user_id").getAsLong() : null;

                            AppUser appUser = null;
                            if (appUserId != null) {
                                appUser = appUserRepository.findById(appUserId).orElse(null);
                            }

                            if (appUser == null && appUserName != null && !appUserName.trim().isEmpty()) {
                                logger.info("DEBUG: Attempting to resolve user with name: {}", appUserName);
                                appUser = entityResolutionService.resolveUser(appUserName).orElse(null);
                                if (appUser == null) {
                                    logger.info("DEBUG: User with name {} not resolved. Triggering confirmation flow.", appUserName);
                                } else {
                                    logger.info("DEBUG: User with name {} resolved to AppUser ID: {}", appUserName, appUser.getId());
                                }
                            }

                            if (appUser == null) {
                                if (appUserName == null || appUserName.trim().isEmpty()) {
                                    logger.warn("appUserName is null or empty for transaction-related message. Cannot trigger user confirmation flow.");
                                    sendMessage(senderId, "Không thể xác định tên người dùng. Vui lòng cung cấp tên người dùng rõ ràng hơn.").subscribe();
                                    return;
                                }
                                logger.info("DEBUG: appUser is still null after resolution attempt. Triggering confirmation flow for unresolved name: {}", appUserName);
                                triggerUserConfirmationFlow(senderId, appUserName, messageText);
                                return;
                            }

                            BigDecimal amount = null;
                            if (jsonResponse.has("amount") && !jsonResponse.get("amount").isJsonNull()) {
                                try {
                                    amount = new BigDecimal(jsonResponse.get("amount").getAsString());
                                } catch (NumberFormatException e) {
                                    logger.error("Error parsing amount to BigDecimal: {}. {}", jsonResponse.get("amount").getAsString(), e.getMessage());
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
                                        logger.error("Error parsing transaction_date for DebtRecord: {}. {}", dueDateStr, e.getMessage());
                                    }
                                }

                                DebtRecord debtRecord = new DebtRecord();
                                debtRecord.setZaloMessageId(savedMessage.getId());
                                debtRecord.setAmount(amount);
                                debtRecord.setReason(reason);
                                debtRecord.setDueDate(dueDate);

                                if ("USER_OWES_OTHER".equals(transactionAnalysisResult.getDebtDirection())) {
                                    debtRecord.setDebtorUser(senderAppUser);
                                    debtRecord.setCreditorUser(appUser);
                                } else if ("OTHER_OWES_USER".equals(transactionAnalysisResult.getDebtDirection())) {
                                    debtRecord.setDebtorUser(appUser);
                                    debtRecord.setCreditorUser(senderAppUser);
                                } else {
                                    logger.error("Unknown debt direction: {}", transactionAnalysisResult.getDebtDirection());
                                    sendMessage(senderId, "Không thể xác định hướng nợ. Vui lòng thử lại.").subscribe();
                                    return;
                                }
                                debtRecordRepository.save(debtRecord);
                                logger.info("Saved new DebtRecord with ID: {} for Debtor User ID: {} and Creditor User ID: {}", debtRecord.getId(), debtRecord.getDebtorUser().getId(), debtRecord.getCreditorUser().getId());
                            } else if ("payment".equals(transactionAnalysisResult.getTransactionType())) {
                                PaymentRecord paymentRecord = new PaymentRecord();
                                paymentRecord.setZaloMessageId(savedMessage.getId());
                                paymentRecord.setAppUser(appUser);
                                paymentRecord.setAmount(amount);
                                paymentRecord.setReason(reason);
                                paymentRecordRepository.save(paymentRecord);
                                logger.info("Saved new PaymentRecord with ID: {} for AppUser ID: {}", paymentRecord.getId(), paymentRecord.getAppUser().getId());
                            } else {
                                logger.info("Unknown transaction type: {}", transactionAnalysisResult.getTransactionType());
                            }

                        } catch (JsonSyntaxException e) {
                            logger.error("Error parsing transaction analysis JSON: {}", e.getMessage());
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
                    logger.info("Zalo Token Endpoint Response: {}", response);
                    JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                    if (jsonResponse.has("error")) {
                        String errorDescription = jsonResponse.get("error_description").getAsString();
                        logger.error("Error from Zalo token endpoint: {}", errorDescription);
                        throw new RuntimeException("Error obtaining access token: " + errorDescription);
                    }
                    String accessToken = jsonResponse.get("access_token").getAsString();
                    String refreshToken = jsonResponse.get("refresh_token").getAsString();
                    zaloConfig.setAccessToken(accessToken);
                    zaloConfig.setRefreshToken(refreshToken);
                    logger.info("Access Token: {}", accessToken);
                    logger.info("Refresh Token: {}", refreshToken);
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

    public Mono<String> readMessages(List<String> targetUserIds) {
        // TODO: Validate Zalo API endpoints /oa/latestmsg and /oa/message with official documentation once accessible.
        logger.info("Scheduled task: Reading Zalo messages...");
        String accessToken = zaloConfig.getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            logger.error("Zalo access token is not configured.");
            return Mono.just("Error: Zalo access token is not configured.");
        }

        if (targetUserIds != null && !targetUserIds.isEmpty()) {
            // Logic for specific users
            return Flux.fromIterable(targetUserIds)
                    .flatMap(userId -> {
                        logger.info("Fetching messages for specific user ID: {}", userId);
                        // Assuming /oa/message can take user_id directly or we need to find conversation_id first.
                        // For now, let's assume conversation_id is equivalent to user_id for direct messages or can be used interchangeably.
                        // This is a placeholder and needs validation with actual Zalo API docs.
                        return webClient.get()
                                .uri(uriBuilder -> uriBuilder.path("/oa/message")
                                        .queryParam("access_token", accessToken)
                                        .queryParam("user_id", userId) // Assuming user_id can be used here
                                        .queryParam("offset", 0)
                                        .queryParam("count", 50)
                                        .build())
                                .retrieve()
                                .bodyToMono(String.class)
                                .flatMap(messageResponse -> {
                                    try {
                                        JsonObject messageJsonResponse = JsonParser.parseString(messageResponse).getAsJsonObject();
                                        if (messageJsonResponse.has("data") && messageJsonResponse.get("data").isJsonArray()) {
                                            return Flux.fromIterable(messageJsonResponse.get("data").getAsJsonArray())
                                                    .flatMap(messageElement -> {
                                                        if (messageElement.isJsonObject()) {
                                                            JsonObject message = messageElement.getAsJsonObject();
                                                            if (message.has("message") && message.get("message").isJsonObject() &&
                                                                    message.get("message").getAsJsonObject().has("text") &&
                                                                    !message.get("message").getAsJsonObject().get("text").isJsonNull()) {
                                                                String messageText = message.get("message").getAsJsonObject().get("text").getAsString();
                                                                String senderId = message.has("from_id") && !message.get("from_id").isJsonNull() ? message.get("from_id").getAsString() : "unknown";
                                                                logger.info("Processing message from {}: {}", senderId, messageText);
                                                                return processIncomingZaloMessage(senderId, messageText);
                                                            }
                                                        }
                                                        return Mono.empty();
                                                    })
                                                    .then(Mono.just("Processed messages for user " + userId));
                                        }
                                        return Mono.just("No messages found for user " + userId);
                                    } catch (JsonSyntaxException e) {
                                        logger.error("Error parsing Zalo API message response for user {}: {}", userId, e.getMessage());
                                        return Mono.just("Error parsing Zalo API message response");
                                    }
                                })
                                .onErrorResume(e -> {
                                    logger.error("Error fetching messages for user {}: {}", userId, e.getMessage());
                                    return Mono.just("Error fetching messages");
                                });
                    })
                    .collectList()
                    .map(results -> "Successfully processed Zalo API messages for specified users.");
        } else {
            // Existing logic for all conversations
            return webClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/oa/latestmsg")
                            .queryParam("access_token", accessToken)
                            .queryParam("offset", 0)
                            .queryParam("count", 50)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .flatMap(response -> {
                        try {
                            JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                            List<String> conversationIds = new CopyOnWriteArrayList<>();
                            if (jsonResponse.has("data") && jsonResponse.get("data").isJsonArray()) {
                                jsonResponse.get("data").getAsJsonArray().forEach(element -> {
                                    if (element.isJsonObject()) {
                                        JsonObject conversation = element.getAsJsonObject();
                                        if (conversation.has("conversation_id") && !conversation.get("conversation_id").isJsonNull()) {
                                            String conversationId = conversation.get("conversation_id").getAsString();
                                            logger.info("Extracted Conversation ID: {}", conversationId);
                                            conversationIds.add(conversationId);
                                        }
                                    }
                                });
                            }

                            // Now iterate through conversation IDs and fetch messages for each
                            return Flux.fromIterable(conversationIds)
                                    .flatMap(conversationId -> {
                                        logger.info("Fetching messages for conversation ID: {}", conversationId);
                                        // Assuming the endpoint for getting messages in a conversation is /oa/message
                                        return webClient.get()
                                                .uri(uriBuilder -> uriBuilder.path("/oa/message")
                                                        .queryParam("access_token", accessToken)
                                                        .queryParam("conversation_id", conversationId)
                                                        .queryParam("offset", 0)
                                                        .queryParam("count", 50)
                                                        .build())
                                                .retrieve()
                                                .bodyToMono(String.class)
                                                .flatMap(messageResponse -> {
                                                    try {
                                                        JsonObject messageJsonResponse = JsonParser.parseString(messageResponse).getAsJsonObject();
                                                        if (messageJsonResponse.has("data") && messageJsonResponse.get("data").isJsonArray()) {
                                                            return Flux.fromIterable(messageJsonResponse.get("data").getAsJsonArray())
                                                                    .flatMap(messageElement -> {
                                                                        if (messageElement.isJsonObject()) {
                                                                            JsonObject message = messageElement.getAsJsonObject();
                                                                            if (message.has("message") && message.get("message").isJsonObject() &&
                                                                                    message.get("message").getAsJsonObject().has("text") &&
                                                                                    !message.get("message").getAsJsonObject().get("text").isJsonNull()) {
                                                                                String messageText = message.get("message").getAsJsonObject().get("text").getAsString();
                                                                                String senderId = message.has("from_id") && !message.get("from_id").isJsonNull() ? message.get("from_id").getAsString() : "unknown";
                                                                                logger.info("Processing message from {}: {}", senderId, messageText);
                                                                                return processIncomingZaloMessage(senderId, messageText);
                                                                            }
                                                                        }
                                                                        return Mono.empty();
                                                                    })
                                                                    .then(Mono.just("Processed messages for conversation " + conversationId));
                                                        }
                                                        return Mono.just("No messages found for conversation " + conversationId);
                                                    } catch (JsonSyntaxException e) {
                                                        logger.error("Error parsing Zalo API message response for conversation {}: {}", conversationId, e.getMessage());
                                                        return Mono.just("Error parsing Zalo API message response");
                                                    }
                                                })
                                                .onErrorResume(e -> {
                                                    logger.error("Error fetching messages for conversation {}: {}", conversationId, e.getMessage());
                                                    return Mono.just("Error fetching messages");
                                                });
                                    })
                                    .collectList()
                                    .map(results -> "Successfully processed Zalo API latest messages and fetched individual conversation messages.");

                        } catch (JsonSyntaxException e) {
                            logger.error("Error parsing Zalo API latest messages response: {}", e.getMessage());
                            return Mono.just("Error parsing Zalo API response: " + e.getMessage());
                        }
                    })
                    .onErrorResume(e -> {
                        logger.error("Error calling Zalo API to read messages: {}", e.getMessage());
                        return Mono.just("Error calling Zalo API: " + e.getMessage());
                    });
        }
    }

    private void triggerUserConfirmationFlow(String senderId, String unresolvedName, String originalMessage) {
        PendingUserConfirmation confirmation = new PendingUserConfirmation();
        confirmation.setUnresolvedName(unresolvedName);
        confirmation.setSenderId(senderId);
        confirmation.setStatus(PendingUserConfirmation.ConfirmationStatus.PENDING);
        confirmation.setCreatedAt(LocalDateTime.now());
        confirmation.setMessageId(UUID.randomUUID().toString());
        confirmation.setOriginalMessage(originalMessage);
        pendingUserConfirmationRepository.save(confirmation);

        String confirmationMessage = String.format(
            "Tôi không tìm thấy người dùng nào có tên '%s'. " +
            "Họ có phải là người dùng mới không? (trả lời 'có' hoặc 'không')",
            unresolvedName
        );

        sendMessage(senderId, confirmationMessage).subscribe();
    }

    public Mono<String> sendMessage(String recipientId, String message) {
        JsonObject recipient = new JsonObject();
        recipient.addProperty("user_id", recipientId);

        JsonObject messageBody = new JsonObject();
        messageBody.addProperty("text", message);

        JsonObject requestBody = new JsonObject();
        requestBody.add("recipient", recipient);
        requestBody.add("message", messageBody);

        logger.info("Sending message to recipientId: {} with content: {}", recipientId, message);
            String accessToken = zaloConfig.getAccessToken();
            logger.info("ZaloConfig Access Token: {}", accessToken);
            logger.info("ZaloConfig Refresh Token: {}", zaloConfig.getRefreshToken());
        logger.info("DEBUG: Access Token in sendMessage: {}", accessToken != null ? accessToken.substring(0, Math.min(accessToken.length(), 10)) + "..." : "null");
        logger.info("DEBUG: Refresh Token in sendMessage: {}", zaloConfig.getRefreshToken() != null ? zaloConfig.getRefreshToken().substring(0, Math.min(zaloConfig.getRefreshToken().length(), 10)) + "..." : "null");

        return webClient.post()
                .uri(uriBuilder -> uriBuilder.path("/oa/message").queryParam("access_token", zaloConfig.getAccessToken()).build())
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class);
    }

    private Mono<String> getSummaryForAppUser(AppUser appUser, String summaryType) {
        Mono<BigDecimal> totalDebtMono = Mono.just(debtRecordRepository.sumAmountWhereUserIsCreditor(appUser));
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

    @Transactional
    public void handleConfirmationResponse(String senderId, String response) {
        logger.info("DEBUG: Entering handleConfirmationResponse for senderId: {} with response: {}", senderId, response);
        Optional<PendingUserConfirmation> pendingConfirmationOptional = pendingUserConfirmationRepository.findBySenderIdAndStatus(senderId, PendingUserConfirmation.ConfirmationStatus.PENDING);
        if (pendingConfirmationOptional.isEmpty()) {
            logger.info("DEBUG: No pending confirmation found for senderId: {}", senderId);
            sendMessage(senderId, "Không có yêu cầu xác nhận nào đang chờ xử lý.").subscribe();
            return;
        }

        PendingUserConfirmation confirmation = pendingConfirmationOptional.get();
        String lowerCaseResponse = response.trim().toLowerCase();

            if (lowerCaseResponse.equals("có")) {
                // User confirmed the name
                AppUser newAppUser = new AppUser();
                newAppUser.setName(confirmation.getUnresolvedName());
                newAppUser.setZaloId(UUID.randomUUID().toString()); // Generate a unique Zalo ID for the new user
                appUserRepository.save(newAppUser);
                logger.info("DEBUG: Created new AppUser with ID: {} and name: {}", newAppUser.getId(), newAppUser.getName());

                UserAlias userAlias = new UserAlias();
                userAlias.setAlias(senderId);
                userAlias.setAppUser(newAppUser);
                userAliasRepository.save(userAlias);
                logger.info("DEBUG: Created new UserAlias for senderId: {} linked to AppUser ID: {}", senderId, newAppUser.getId());

                confirmation.setStatus(PendingUserConfirmation.ConfirmationStatus.CONFIRMED);
                pendingUserConfirmationRepository.save(confirmation);
                logger.debug("DEBUG: PendingUserConfirmation status updated to CONFIRMED for senderId: {}", senderId);
                sendMessage(senderId, "Đã xác nhận tên \"" + confirmation.getUnresolvedName() + "\". Đang xử lý lại tin nhắn gốc.").subscribe();

                // Re-process the original message
                processIncomingZaloMessage(senderId, confirmation.getOriginalMessage())
                        .subscribe(
                                result -> logger.info("Re-processed original message after confirmation: {}", confirmation.getOriginalMessage()),
                                error -> logger.error("Error re-processing original message after confirmation: {}", error.getMessage())
                        );

            } else if (lowerCaseResponse.equals("không")) {
                confirmation.setStatus(PendingUserConfirmation.ConfirmationStatus.DENIED);
                pendingUserConfirmationRepository.save(confirmation);
                sendMessage(senderId, "Đã hủy xác nhận. Vui lòng nhập lại tin nhắn với tên rõ ràng hơn.").subscribe();
            } else {
                sendMessage(senderId, "Phản hồi không hợp lệ. Vui lòng trả lời 'có' hoặc 'không'.").subscribe();
            }
    }
}