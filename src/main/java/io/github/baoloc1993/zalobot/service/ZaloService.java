package io.github.baoloc1993.zalobot.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.baoloc1993.zalobot.config.ZaloConfig;
import io.github.baoloc1993.zalobot.model.AppUser;
import io.github.baoloc1993.zalobot.model.DebtRecord;
import io.github.baoloc1993.zalobot.model.PaymentRecord;
import io.github.baoloc1993.zalobot.model.PendingUserConfirmation;
import io.github.baoloc1993.zalobot.model.TransactionAnalysisResult;
import io.github.baoloc1993.zalobot.model.ZaloMessage;
import io.github.baoloc1993.zalobot.model.ZaloToken;
import io.github.baoloc1993.zalobot.model.gemini.GeminiMessage;
import io.github.baoloc1993.zalobot.repository.AppUserRepository;
import io.github.baoloc1993.zalobot.repository.DebtRecordRepository;
import io.github.baoloc1993.zalobot.repository.PaymentRecordRepository;
import io.github.baoloc1993.zalobot.repository.PendingUserConfirmationRepository;
import io.github.baoloc1993.zalobot.repository.UserAliasRepository;
import io.github.baoloc1993.zalobot.repository.ZaloMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ZaloService {
    private static final Logger logger = LoggerFactory.getLogger(ZaloService.class);

    private static final String ZALO_OPENAPI_BASE_URL = "https://openapi.zalo.me/v2.0";
    private static final String ZALO_OAUTH_BASE_URL = "https://oauth.zalo.me/v4";

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
    private final ZaloTokenService zaloTokenService;
    private final Gson gson = new Gson();

    private final Map<String, List<GeminiMessage>> conversationHistories = new ConcurrentHashMap<>();

    public List<GeminiMessage> getConversationHistory(String senderId) {
        return conversationHistories.computeIfAbsent(senderId, k -> new CopyOnWriteArrayList<>());
    }


    @Transactional
    public Mono<Void> processIncomingZaloMessage(String senderId, String messageContent, String messageId) {
        logger.info("Processing incoming Zalo message {} from senderId {}", messageId, senderId);
        logger.debug("Message content: {}", messageContent);

        // Save incoming message
        ZaloMessage zaloMessage = new ZaloMessage();
        zaloMessage.setSenderId(senderId);
        zaloMessage.setReceiverId(zaloConfig.getOaId()); // Assuming the bot is the receiver
        zaloMessage.setMessageContent(messageContent);
        zaloMessage.setMessageType("text");
        zaloMessage.setTimestamp(LocalDateTime.now());
        zaloMessage.setDirection(ZaloMessage.Direction.inbound);
        zaloMessage.setZaloMessageId(messageId);
        zaloMessageRepository.save(zaloMessage);

        // Check for pending confirmations first
        return Mono.justOrEmpty(pendingUserConfirmationRepository.findByZaloUserIdAndConfirmationType(senderId, PendingUserConfirmation.ConfirmationType.PENDING))
                .flatMap(pendingConfirmation -> handleConfirmationResponse(senderId, messageContent, pendingConfirmation))
                .switchIfEmpty(Mono.defer(() -> {
                    // If no pending confirmation, analyze the message
                    return geminiApiClient.analyzeMessageForDebt(messageContent)
                            .flatMap(analysisResult -> saveTransactionAnalysisResult(analysisResult, senderId, messageContent, messageId))
                            .then();
                }))
                .then();
    }

    @Transactional
    public Mono<Void> handleConfirmationResponse(String senderId, String userResponse, PendingUserConfirmation pendingConfirmation) {
        if ("có".equalsIgnoreCase(userResponse)) {
            // User confirmed, create new AppUser
            AppUser newAppUser = new AppUser();
            newAppUser.setName(pendingConfirmation.getProposedAppUserName());
            // Assuming Zalo ID is not available for the new user yet, or might be the sender's ID if that's the logic.
            // For now, let's leave it null or set a placeholder.
            newAppUser.setZaloId(null); // Or some other logic to determine Zalo ID

            return Mono.fromCallable(() -> appUserRepository.save(newAppUser))
                    .flatMap(savedUser -> {
                        // Now that the user is created, re-process the original message
                        TransactionAnalysisResult analysisResult = gson.fromJson(pendingConfirmation.getAnalysisResultJson(), TransactionAnalysisResult.class);
                        return saveTransactionAnalysisResult(analysisResult, senderId, pendingConfirmation.getOriginalMessageContent(), analysisResult.getZaloMessageId());
                    })
                    .doOnSuccess(v -> {
                        pendingUserConfirmationRepository.delete(pendingConfirmation);
                        sendMessage(senderId, "Đã tạo người dùng mới và ghi nhận giao dịch.");
                    }).then();
        } else if ("không".equalsIgnoreCase(userResponse)) {
            // User declined, delete the pending confirmation
            return Mono.fromRunnable(() -> pendingUserConfirmationRepository.delete(pendingConfirmation))
                    .doOnSuccess(v -> sendMessage(senderId, "Đã hủy thao tác. Giao dịch không được ghi nhận."))
                    .then();
        } else {
            // Unrecognized response, ask again
            return sendMessage(senderId, "Vui lòng trả lời 'Có' hoặc 'Không'.").then();
        }
    }

    public ZaloService(ZaloConfig zaloConfig, WebClient.Builder webClientBuilder, GeminiApiClient geminiApiClient, ZaloMessageRepository zaloMessageRepository, DebtRecordRepository debtRecordRepository, AppUserRepository appUserRepository, PaymentRecordRepository paymentRecordRepository, EntityResolutionService entityResolutionService, PendingUserConfirmationRepository pendingUserConfirmationRepository, UserAliasRepository userAliasRepository, ZaloTokenService zaloTokenService) {
        this.zaloConfig = zaloConfig;
        this.webClient = webClientBuilder.baseUrl(ZALO_OPENAPI_BASE_URL).build();
        this.geminiApiClient = geminiApiClient;
        this.zaloMessageRepository = zaloMessageRepository;
        this.debtRecordRepository = debtRecordRepository;
        this.appUserRepository = appUserRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.entityResolutionService = entityResolutionService;
        this.pendingUserConfirmationRepository = pendingUserConfirmationRepository;
        this.userAliasRepository = userAliasRepository;
        this.zaloTokenService = zaloTokenService;
    }

    public ZaloConfig getZaloConfig() {
        return zaloConfig;
    }

    public Mono<String> sendMessage(String userId, String message) {
        return getValidAccessToken()
                .flatMap(accessToken -> {
                    JsonObject recipient = new JsonObject();
                    recipient.addProperty("user_id", userId);

                    JsonObject messageObject = new JsonObject();
                    messageObject.addProperty("text", message);

                    JsonObject data = new JsonObject();
                    data.add("recipient", recipient);
                    data.add("message", messageObject);

                    return webClient.post()
                            .uri("/oa/message")
                            .header("Content-Type", "application/json")
                            .header("access_token", accessToken)
                            .bodyValue(data.toString())
                            .retrieve()
                            .bodyToMono(String.class)
                            .doOnSuccess(response -> logger.info("Sent message to user {}. Response: {}", userId, response))
                            .doOnError(error -> logger.error("Failed to send message to user {}: {}", userId, error.getMessage()));
                });
    }

    public Mono<String> getProfile(String userId) {
        return getValidAccessToken()
                .flatMap(accessToken -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("user_id", userId);
                    String dataJsonString = data.toString();
                    String encodedDataJsonString = URLEncoder.encode(dataJsonString, StandardCharsets.UTF_8);
                    String fullUriString = ZALO_OPENAPI_BASE_URL + "/oa/get_profile?data=" + encodedDataJsonString;
                    logger.debug("Calling Zalo profile API: {}", fullUriString);
                    try {
                        URI uri = new URI(fullUriString);
                        return webClient.get()
                                .uri(uri)
                                .header("access_token", accessToken)
                                .retrieve()
                                .bodyToMono(String.class);
                    } catch (URISyntaxException e) {
                        logger.error("Error creating URI: {}", e.getMessage());
                        return Mono.error(new RuntimeException("Invalid URI", e));
                    }
                });
    }

    public Mono<ZaloToken> exchangeCodeForAccessToken(String oauthCode) {
        logger.info("Exchanging OAuth code for access token...");
        String requestBody = "app_id=" + zaloConfig.getAppId() +
                "&code=" + oauthCode +
                "&grant_type=authorization_code";

        return WebClient.builder().baseUrl(ZALO_OAUTH_BASE_URL).build().post()
                .uri("/access_token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("secret_key", zaloConfig.getAppSecret())
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(response -> {
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                        String accessToken = jsonResponse.get("access_token").getAsString();
                        String refreshToken = jsonResponse.get("refresh_token").getAsString();
                        Long expiresIn = jsonResponse.get("expires_in").getAsLong();
                        ZaloToken savedToken = zaloTokenService.saveToken(accessToken, refreshToken, expiresIn);
                        return Mono.just(savedToken);
                    } catch (Exception e) {
                        logger.error("Failed to parse Zalo token exchange response: {}", e.getMessage());
                        return Mono.error(new RuntimeException("Failed to parse Zalo token exchange response.", e));
                    }
                })
                .doOnError(error -> logger.error("Error exchanging OAuth code for access token: {}", error.getMessage()));
    }


    public Mono<String> getConversation(String userId, int offset, int count) {
        return getValidAccessToken()
                .flatMap(accessToken -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("user_id", userId);
                    data.addProperty("offset", offset);
                    data.addProperty("count", count);
                    String dataJsonString = data.toString();
                    String encodedDataJsonString = URLEncoder.encode(dataJsonString, StandardCharsets.UTF_8);
                    String fullUriString = ZALO_OPENAPI_BASE_URL + "/oa/conversation?data=" + encodedDataJsonString;
                    logger.debug("Calling Zalo conversation API: {}", fullUriString);
                    try {
                        URI uri = new URI(fullUriString);
                        return webClient.get()
                                .uri(uri)
                                .header("access_token", accessToken)
                                .retrieve()
                                .bodyToMono(String.class);
                    } catch (URISyntaxException e) {
                        logger.error("Error creating URI: {}", e.getMessage());
                        return Mono.error(new RuntimeException("Invalid URI", e));
                    }
                });
    }

    private Mono<String> getValidAccessToken() {
        return Mono.justOrEmpty(zaloTokenService.getLatestToken())
                .switchIfEmpty(Mono.error(new RuntimeException("No Zalo token found in DB. Please obtain one first.")))
                .flatMap(token -> {
                    if (zaloTokenService.isAccessTokenExpired(token)) {
                        logger.info("Access token expired, refreshing...");
                        return refreshAccessToken()
                                .map(ZaloToken::getAccessToken);
                    } else {
                        return Mono.just(token.getAccessToken());
                    }
                });
    }

    public Mono<ZaloToken> refreshAccessToken() {
        logger.info("Refreshing Zalo access token...");

        return Mono.justOrEmpty(zaloTokenService.getLatestToken())
                .switchIfEmpty(Mono.error(new RuntimeException("Zalo refresh token is not configured in DB.")))
                .flatMap(token -> {
                    String refreshToken = token.getRefreshToken();
                    String requestBody = "app_id=" + zaloConfig.getAppId() +
                            "&secret_key=" + zaloConfig.getAppSecret() +
                            "&refresh_token=" + refreshToken +
                            "&grant_type=refresh_token";

                    return WebClient.builder().baseUrl(ZALO_OAUTH_BASE_URL).build().post()
                            .uri("/access_token")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("secret_key", zaloConfig.getAppSecret())
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .flatMap(response -> {
                                try {
                                    JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                                    if (jsonResponse.has("access_token")) {
                                        String newAccessToken = jsonResponse.get("access_token").getAsString();
                                        String newRefreshToken = jsonResponse.get("refresh_token").getAsString();
                                        Long expiresIn = jsonResponse.get("expires_in").getAsLong();
                                        ZaloToken savedToken = zaloTokenService.saveToken(newAccessToken, newRefreshToken, expiresIn);
                                        logger.info("Successfully refreshed and saved new Zalo access token.");
                                        return Mono.just(savedToken);
                                    } else {
                                        // No access_token in the response, so it only contains Zalo error details
                                        logger.error("Zalo token refresh response does not contain 'access_token'. Response: {}", response);
                                        return Mono.error(new RuntimeException("Zalo token refresh response does not contain 'access_token'."));
                                    }
                                } catch (Exception e) {
                                    logger.error("Failed to parse Zalo token refresh response: {}", e.getMessage());
                                    return Mono.error(new RuntimeException("Failed to parse Zalo token refresh response.", e));
                                }
                            })
                            .doOnError(error -> logger.error("Error refreshing Zalo access token: {}", error.getMessage()));
                });
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


    @Transactional
    public Mono<Void> saveTransactionAnalysisResult(TransactionAnalysisResult analysisResult, String zaloUserId, String messageContent, String zaloMessageId) {
        logger.info("Attempting to save transaction analysis result for user {}: {}", zaloUserId, analysisResult);
        if (!analysisResult.isTransactionRelated()) {
            return Mono.empty();
        }

        return Mono.justOrEmpty(appUserRepository.findByZaloId(zaloUserId))
                .switchIfEmpty(Mono.defer(() -> {
                    logger.info("AppUser not found for zaloId: {}, creating new AppUser.", zaloUserId);
                    AppUser newAppUser = new AppUser();
                    newAppUser.setZaloId(zaloUserId);
                    newAppUser.setName("ZaloUser_" + zaloUserId); // Default name
                    return Mono.just(appUserRepository.save(newAppUser));
                }))
                .flatMap(currentUser -> {
                    String transactionType = analysisResult.getTransactionType();
                    String debtDirection = analysisResult.getDebtDirection();
                    String appUserName = analysisResult.getAppUserName();
                    BigDecimal amount = analysisResult.getAmount() != null ? BigDecimal.valueOf(analysisResult.getAmount()) : null;
                    String reason = analysisResult.getReason();
                    LocalDate transactionDate = analysisResult.getTransactionDate() != null ? LocalDate.parse(analysisResult.getTransactionDate()) : null;

                    if ("debt".equals(transactionType)) {
                        return entityResolutionService.resolveAppUser(appUserName)
                                .flatMap(otherUserOptional -> {
                                    if (otherUserOptional.isPresent()) {
                                        AppUser otherUser = otherUserOptional.get();
                                        DebtRecord debtRecord = new DebtRecord();
                                        debtRecord.setZaloMessageId(zaloMessageId);
                                        debtRecord.setAmount(amount);
                                        debtRecord.setReason(reason);
                                        debtRecord.setDueDate(transactionDate);

                                        if ("USER_OWES_OTHER".equals(debtDirection)) {
                                            debtRecord.setDebtorUser(currentUser);
                                            debtRecord.setCreditorUser(otherUser);
                                        } else if ("OTHER_OWES_USER".equals(debtDirection)) {
                                            debtRecord.setDebtorUser(otherUser);
                                            debtRecord.setCreditorUser(currentUser);
                                        } else {
                                            return Mono.error(new RuntimeException("Invalid debt direction: " + debtDirection));
                                        }
                                        return Mono.fromCallable(() -> debtRecordRepository.save(debtRecord)).then();
                                    } else {
                                        // AppUser not found, send confirmation message
                                        String confirmationMessage = String.format("Không tìm thấy người dùng '%s'. Bạn có muốn tạo người dùng mới với tên này không? (Có/Không)", appUserName);
                                        return sendMessage(zaloUserId, confirmationMessage)
                                                .flatMap(messageId -> {
                                                    PendingUserConfirmation pendingConfirmation = new PendingUserConfirmation();
                                                    pendingConfirmation.setZaloUserId(zaloUserId);
                                                    pendingConfirmation.setOriginalMessageContent(messageContent);
                                                    pendingConfirmation.setAnalysisResultJson(gson.toJson(analysisResult));
                                                    pendingConfirmation.setConfirmationType(PendingUserConfirmation.ConfirmationType.CREATE_APP_USER);
                                                    pendingConfirmation.setProposedAppUserName(appUserName);
                                                    pendingConfirmation.setZaloMessageId(zaloMessageId); // Store original Zalo message ID
                                                    return Mono.fromCallable(() -> pendingUserConfirmationRepository.save(pendingConfirmation)).then();
                                                });
                                    }
                                });
                    } else if ("payment".equals(transactionType)) {
                        // For payments, we assume the current user is making/receiving the payment
                        PaymentRecord paymentRecord = new PaymentRecord();
                        paymentRecord.setZaloMessageId(zaloMessageId);
                        paymentRecord.setAppUser(currentUser);
                        paymentRecord.setAmount(amount);
                        paymentRecord.setReason(reason);
                        return Mono.fromCallable(() -> paymentRecordRepository.save(paymentRecord)).then();
                    } else {
                        logger.warn("Unknown transaction type: {}", transactionType);
                        return Mono.empty();
                    }
                });
    }



    public Mono<TransactionAnalysisResult> analyzeMessageForDebt(String messageContent, String zaloMessageId) {
        return geminiApiClient.analyzeMessageForDebt(messageContent)
                .map(analysisResult -> {
                    analysisResult.setZaloMessageId(zaloMessageId);
                    return analysisResult;
                });
    }

    @Transactional
    public Mono<Void> handleConfirmationResponse(String zaloUserId, String confirmationResponse, String zaloMessageId) {
        return Mono.fromCallable(() -> pendingUserConfirmationRepository.findByZaloUserIdAndZaloMessageId(zaloUserId, zaloMessageId))
                .flatMap(optionalPendingConfirmation -> {
                    if (optionalPendingConfirmation.isEmpty()) {
                        logger.warn("No pending confirmation found for Zalo user {} with message ID {}", zaloUserId, zaloMessageId);
                        return Mono.empty();
                    }

                    PendingUserConfirmation pendingConfirmation = optionalPendingConfirmation.get();
                    pendingUserConfirmationRepository.delete(pendingConfirmation);

                    if (confirmationResponse.equalsIgnoreCase("Có")) {
                        logger.info("User {} confirmed creation of AppUser {}", zaloUserId, pendingConfirmation.getProposedAppUserName());
                        AppUser newAppUser = new AppUser();
                        newAppUser.setName(pendingConfirmation.getProposedAppUserName());
                        return Mono.fromCallable(() -> appUserRepository.save(newAppUser))
                                .flatMap(savedAppUser -> {
                                    logger.info("New AppUser {} created. Re-processing original message.", savedAppUser.getName());
                                    TransactionAnalysisResult analysisResult = gson.fromJson(pendingConfirmation.getAnalysisResultJson(), TransactionAnalysisResult.class);
                                    return saveTransactionAnalysisResult(analysisResult, zaloUserId, pendingConfirmation.getOriginalMessageContent(), analysisResult.getZaloMessageId())
                                            .then(sendMessage(zaloUserId, String.format("Đã tạo người dùng '%s' và xử lý giao dịch của bạn.", savedAppUser.getName())));
                                });
                    } else {
                        logger.info("User {} declined creation of AppUser {}", zaloUserId, pendingConfirmation.getProposedAppUserName());
                        return sendMessage(zaloUserId, String.format("Đã hủy tạo người dùng '%s'. Giao dịch không được xử lý.", pendingConfirmation.getProposedAppUserName()));
                    }
                })
                .then();
    }



    /** Parses the comma-separated {@code zalo.scheduled.users} setting, ignoring blank entries. */
    private List<String> getScheduledUserIds() {
        String userIdsString = zaloConfig.getScheduledUsers();
        if (userIdsString == null || userIdsString.isBlank()) {
            return List.of();
        }
        return Arrays.stream(userIdsString.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public Flux<String> readMessages() {
        List<String> userIds = getScheduledUserIds();
        logger.info("Reading latest messages for {} scheduled user(s).", userIds.size());

        return Flux.fromIterable(userIds)
                .flatMap(userId -> getAndAnalyzeLatestMessage(userId)
                        .map(analysisResult -> "Processed message for user " + userId + ": " + analysisResult.getAnalysisJson()))
                .doOnError(e -> logger.error("Error during message processing: {}", e.getMessage()))
                .doOnComplete(() -> logger.info("Finished processing messages for all configured users."));
    }

    public Mono<TransactionAnalysisResult> getAndAnalyzeLatestMessage(String userId) {
        return getConversation(userId, 0, 1)
                .flatMap(response -> {
                    try {
                        JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                        JsonArray dataArray = jsonResponse.getAsJsonArray("data");
                        if (dataArray != null && dataArray.size() > 0) {
                            JsonObject latestMessage = dataArray.get(0).getAsJsonObject();
                            String messageContent = latestMessage.get("message").getAsString();
                            String messageId = latestMessage.get("msg_id").getAsString();
                            logger.info("Extracted latest message {} for user {}", messageId, userId);
                            return analyzeMessageForDebt(messageContent, messageId)
                                .flatMap(analysisResult -> {
                                    if (analysisResult.isTransactionRelated()) {
                                        return saveTransactionAnalysisResult(analysisResult, userId, messageContent, messageId)
                                                .thenReturn(analysisResult);
                                    } else {
                                        return Mono.just(analysisResult);
                                    }
                                });
                        } else {
                            logger.info("No messages found for user {}", userId);
                            return Mono.empty();
                        }
                    } catch (Exception e) {
                        logger.error("Error parsing conversation response for user {}: {}", userId, e.getMessage());
                        return Mono.error(new RuntimeException("Failed to parse conversation response", e));
                    }
                });
    }

    @Transactional
    public Mono<Void> remindUsersWithUnpaidDebts() {
        List<String> userIds = getScheduledUserIds();
        if (userIds.isEmpty()) {
            logger.info("No scheduled users to remind.");
            return Mono.empty();
        }

        return Flux.fromIterable(userIds)
                .flatMap(userId -> {
                    // Similar to processIncomingZaloMessage, but initiated by the system
                    String messageContent = "Nhắc nợ"; // Example message
                    String messageId = UUID.randomUUID().toString();

                    ZaloMessage reminderMessage = new ZaloMessage();
                    reminderMessage.setSenderId(zaloConfig.getOaId());
                    reminderMessage.setReceiverId(userId);
                    reminderMessage.setMessageContent(messageContent);
                    reminderMessage.setMessageType("text");
                    reminderMessage.setTimestamp(LocalDateTime.now());
                    reminderMessage.setDirection(ZaloMessage.Direction.outbound);
                    reminderMessage.setZaloMessageId(messageId);
                    zaloMessageRepository.save(reminderMessage);

                    return Mono.justOrEmpty(pendingUserConfirmationRepository.findByZaloUserIdAndConfirmationType(userId, PendingUserConfirmation.ConfirmationType.PENDING))
                            .flatMap(pendingConfirmation -> handleConfirmationResponse(userId, "có", pendingConfirmation)) // Auto-confirm for reminders
                            .switchIfEmpty(Mono.defer(() ->
                                    geminiApiClient.analyzeMessageForDebt(messageContent)
                                        .flatMap(analysisResult ->
                                            saveTransactionAnalysisResult(analysisResult, userId, messageContent, messageId))
                                        .then()
                            ));
                })
                .then();
    }
}
