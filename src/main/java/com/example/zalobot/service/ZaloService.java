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
        logger.debug("Entering processIncomingZaloMessage for senderId: {} with message: {}", senderId, messageText);

        // Ensure sender AppUser exists
        AppUser senderAppUser = appUserRepository.findByZaloId(senderId).orElseGet(() -> {
            AppUser newSenderAppUser = new AppUser();
            newSenderAppUser.setZaloId(senderId);
            newSenderAppUser.setName(senderId); // Default name to senderId, can be updated later
            return appUserRepository.save(newSenderAppUser);
        });
        logger.debug("Sender AppUser ID: {} for Zalo ID: {}", senderAppUser.getId(), senderId);

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
        logger.debug("Initial rawData for userZaloMessage: {}", initialRawData);
        userZaloMessage.setRawData(initialRawData);
        ZaloMessage savedMessage = zaloMessageRepository.save(userZaloMessage);
        logger.debug("Saved userZaloMessage with ID: {}, rawData: {}", savedMessage.getId(), savedMessage.getRawData());

        return analyzeMessageForDebt(messageText)
                .doOnNext(transactionAnalysisResult -> {
                    // Update the saved message with debt analysis result
                    String analysisJson = transactionAnalysisResult.getAnalysisJson();
                    logger.debug("Analysis JSON from Gemini: {}", analysisJson);
                    savedMessage.setRawData(analysisJson);
                    zaloMessageRepository.save(savedMessage);
                    logger.debug("Updated savedMessage with analysisJson. New rawData: {}", savedMessage.getRawData());

                    logger.debug("DEBUG: Entering doOnNext block after transaction analysis. isTransactionRelated: {}", transactionAnalysisResult.isTransactionRelated());
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
                            logger.debug("DEBUG: Parsed JSON response from Gemini: {}", jsonResponse.toString());
                            String appUserName = jsonResponse.has("app_user_name") && !jsonResponse.get("app_user_name").isJsonNull() ? jsonResponse.get("app_user_name").getAsString() : null;
                            logger.debug("DEBUG: Extracted appUserName from Gemini response: {}", appUserName);
                            Long appUserId = jsonResponse.has("app_user_id") && !jsonResponse.get("app_user_id").isJsonNull() ? jsonResponse.get("app_user_id").getAsLong() : null;

                            AppUser appUser = null;
                            if (appUserId != null) {
                                appUser = appUserRepository.findById(appUserId).orElse(null);
                            }

                            if (appUser == null && appUserName != null && !appUserName.trim().isEmpty()) {
                                logger.debug("DEBUG: Attempting to resolve user with name: {}", appUserName);
                                appUser = entityResolutionService.resolveUser(appUserName).orElse(null);
                                if (appUser == null) {
                                    logger.debug("DEBUG: User with name {} not resolved. Triggering confirmation flow.", appUserName);
                                } else {
                                    logger.debug("DEBUG: User with name {} resolved to AppUser ID: {}", appUserName, appUser.getId());
                                }
                            }

                            if (appUser == null) {
                                if (appUserName == null || appUserName.trim().isEmpty()) {
                                    logger.warn("appUserName is null or empty for transaction-related message. Cannot trigger user confirmation flow.");
                                    sendMessage(senderId, "Không thể xác định tên người dùng. Vui lòng cung cấp tên người dùng rõ ràng hơn.").subscribe();
                                    return;
                                }
                                logger.debug("DEBUG: appUser is still null after resolution attempt. Triggering confirmation flow for unresolved name: {}", appUserName);
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

    public Mono<String> readMessages() {
        System.out.println("Reading messages from Zalo API...");
        // TODO: Implement actual Zalo API call to read messages
        return Mono.just("Messages read successfully (placeholder)");
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
        logger.debug("DEBUG: Entering handleConfirmationResponse for senderId: {} with response: {}", senderId, response);
        Optional<PendingUserConfirmation> pendingConfirmationOptional = pendingUserConfirmationRepository.findBySenderIdAndStatus(senderId, PendingUserConfirmation.ConfirmationStatus.PENDING);
        if (pendingConfirmationOptional.isEmpty()) {
            logger.debug("DEBUG: No pending confirmation found for senderId: {}", senderId);
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
                logger.debug("DEBUG: Created new AppUser with ID: {} and name: {}", newAppUser.getId(), newAppUser.getName());

                UserAlias userAlias = new UserAlias();
                userAlias.setAlias(senderId);
                userAlias.setAppUser(newAppUser);
                userAliasRepository.save(userAlias);
                logger.debug("DEBUG: Created new UserAlias for senderId: {} linked to AppUser ID: {}", senderId, newAppUser.getId());

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