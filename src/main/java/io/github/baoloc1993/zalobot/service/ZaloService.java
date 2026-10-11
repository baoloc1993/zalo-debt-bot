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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ZaloService {
    private static final Logger logger = LoggerFactory.getLogger(ZaloService.class);

    private static final String ZALO_OPENAPI_BASE_URL = "https://openapi.zalo.me/v2.0";
    private static final String ZALO_OAUTH_BASE_URL = "https://oauth.zalo.me/v4";

    private static final Set<String> YES_ANSWERS = Set.of("có", "co", "yes", "y");
    private static final Set<String> NO_ANSWERS = Set.of("không", "khong", "no", "n");

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
    private final InboundMessageRegistry inboundMessageRegistry;
    private final Gson gson = new Gson();

    private final Map<String, List<GeminiMessage>> conversationHistories = new ConcurrentHashMap<>();

    public List<GeminiMessage> getConversationHistory(String senderId) {
        return conversationHistories.computeIfAbsent(senderId, k -> new CopyOnWriteArrayList<>());
    }


    /**
     * Single entry point for inbound user messages (webhook, scheduled polling and the dev simulator).
     * <p>
     * The message is first registered by its Zalo message id; a message that was already registered
     * (a repeated webhook delivery, the next polling tick, an overlapping run) is skipped. A message
     * that fails during processing is logged and is <em>not</em> retried, so a poison message cannot
     * be reprocessed forever.
     */
    public Mono<Void> processIncomingZaloMessage(String senderId, String messageContent, String messageId) {
        ZaloMessage zaloMessage = new ZaloMessage();
        zaloMessage.setSenderId(senderId);
        zaloMessage.setReceiverId(zaloConfig.getOaId());
        zaloMessage.setMessageContent(messageContent);
        zaloMessage.setMessageType("text");
        zaloMessage.setTimestamp(LocalDateTime.now());
        zaloMessage.setDirection(ZaloMessage.Direction.inbound);
        zaloMessage.setZaloMessageId(messageId);

        if (!inboundMessageRegistry.tryRegister(zaloMessage)) {
            logger.info("Zalo message {} was already processed; skipping.", messageId);
            return Mono.empty();
        }
        logger.info("Processing incoming Zalo message {} from senderId {}", messageId, senderId);

        return Mono.defer(() -> {
                    // A reply to an open "create user?" question takes precedence over normal analysis
                    Optional<PendingUserConfirmation> pending = pendingUserConfirmationRepository
                            .findFirstByZaloUserIdAndStatusOrderByCreatedAtDesc(senderId, PendingUserConfirmation.ConfirmationStatus.PENDING);
                    if (pending.isPresent()) {
                        return handleConfirmationResponse(senderId, messageContent, pending.get());
                    }
                    return analyzeMessageForDebt(messageContent, messageId)
                            .flatMap(analysisResult -> saveTransactionAnalysisResult(analysisResult, senderId, messageContent, messageId));
                })
                .onErrorResume(e -> {
                    logger.warn("Processing of Zalo message {} failed and will not be retried: {}", messageId, e.toString());
                    return Mono.empty();
                });
    }

    /**
     * Handles the user's yes/no answer to a pending "create new user?" question.
     * <ul>
     *   <li>yes: the user is created, the original transaction is recorded, status becomes CONFIRMED</li>
     *   <li>no: nothing is recorded, status becomes DENIED</li>
     *   <li>anything else: the question is repeated and the confirmation stays PENDING</li>
     * </ul>
     */
    public Mono<Void> handleConfirmationResponse(String senderId, String userResponse, PendingUserConfirmation pendingConfirmation) {
        String answer = userResponse == null ? "" : userResponse.trim().toLowerCase(Locale.ROOT);
        String proposedName = pendingConfirmation.getProposedAppUserName();

        if (YES_ANSWERS.contains(answer)) {
            logger.info("User {} confirmed creation of AppUser {}", senderId, proposedName);
            return Mono.fromCallable(() -> appUserRepository.findByName(proposedName).orElseGet(() -> {
                        AppUser newAppUser = new AppUser();
                        newAppUser.setName(proposedName);
                        return appUserRepository.save(newAppUser);
                    }))
                    .flatMap(savedUser -> {
                        // The person now exists, so re-processing the original message records the transaction
                        TransactionAnalysisResult analysisResult = gson.fromJson(pendingConfirmation.getAnalysisResultJson(), TransactionAnalysisResult.class);
                        return saveTransactionAnalysisResult(analysisResult, senderId,
                                pendingConfirmation.getOriginalMessageContent(), pendingConfirmation.getZaloMessageId());
                    })
                    // Mark CONFIRMED only after the transaction was stored; on failure it stays PENDING
                    .then(Mono.fromRunnable(() -> {
                        pendingConfirmation.setStatus(PendingUserConfirmation.ConfirmationStatus.CONFIRMED);
                        pendingUserConfirmationRepository.save(pendingConfirmation);
                    }))
                    .then(Mono.defer(() -> notifyUser(senderId,
                            String.format("Đã tạo người dùng '%s' và ghi nhận giao dịch.", proposedName))));
        }

        if (NO_ANSWERS.contains(answer)) {
            logger.info("User {} declined creation of AppUser {}", senderId, proposedName);
            return Mono.fromRunnable(() -> {
                        pendingConfirmation.setStatus(PendingUserConfirmation.ConfirmationStatus.DENIED);
                        pendingUserConfirmationRepository.save(pendingConfirmation);
                    })
                    .then(Mono.defer(() -> notifyUser(senderId,
                            String.format("Đã hủy tạo người dùng '%s'. Giao dịch không được ghi nhận.", proposedName))));
        }

        return notifyUser(senderId, "Vui lòng trả lời 'Có' hoặc 'Không'.");
    }

    /** Sends a Zalo message; a delivery failure is logged but never fails the surrounding flow. */
    private Mono<Void> notifyUser(String userId, String text) {
        return Mono.defer(() -> sendMessage(userId, text))
                .then()
                .onErrorResume(e -> {
                    logger.warn("Could not notify user {}: {}", userId, e.getMessage());
                    return Mono.empty();
                });
    }

    public ZaloService(ZaloConfig zaloConfig, WebClient.Builder webClientBuilder, GeminiApiClient geminiApiClient, ZaloMessageRepository zaloMessageRepository, DebtRecordRepository debtRecordRepository, AppUserRepository appUserRepository, PaymentRecordRepository paymentRecordRepository, EntityResolutionService entityResolutionService, PendingUserConfirmationRepository pendingUserConfirmationRepository, UserAliasRepository userAliasRepository, ZaloTokenService zaloTokenService, InboundMessageRegistry inboundMessageRegistry) {
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
        this.inboundMessageRegistry = inboundMessageRegistry;
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
                    LocalDate transactionDate = parseDate(analysisResult.getTransactionDate());

                    if ("debt".equals(transactionType)) {
                        if (appUserName == null || appUserName.isBlank()) {
                            logger.warn("Debt transaction without a counterparty name; ignoring.");
                            return Mono.empty();
                        }
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
                                        // Unknown person: remember the transaction and ask the sender to confirm
                                        PendingUserConfirmation pendingConfirmation = new PendingUserConfirmation();
                                        pendingConfirmation.setZaloUserId(zaloUserId);
                                        pendingConfirmation.setOriginalMessageContent(messageContent);
                                        pendingConfirmation.setAnalysisResultJson(gson.toJson(analysisResult));
                                        pendingConfirmation.setConfirmationType(PendingUserConfirmation.ConfirmationType.CREATE_APP_USER);
                                        pendingConfirmation.setStatus(PendingUserConfirmation.ConfirmationStatus.PENDING);
                                        pendingConfirmation.setProposedAppUserName(appUserName);
                                        pendingConfirmation.setZaloMessageId(zaloMessageId);
                                        String confirmationMessage = String.format("Không tìm thấy người dùng '%s'. Bạn có muốn tạo người dùng mới với tên này không? (Có/Không)", appUserName);
                                        return Mono.fromCallable(() -> pendingUserConfirmationRepository.save(pendingConfirmation))
                                                .then(Mono.defer(() -> notifyUser(zaloUserId, confirmationMessage)));
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



    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            logger.warn("Ignoring unparsable transaction date.");
            return null;
        }
    }

    public Mono<TransactionAnalysisResult> analyzeMessageForDebt(String messageContent, String zaloMessageId) {
        return geminiApiClient.analyzeMessageForDebt(messageContent)
                .map(analysisResult -> {
                    analysisResult.setZaloMessageId(zaloMessageId);
                    return analysisResult;
                });
    }

    /**
     * Polls the latest message of every configured user and feeds it through
     * {@link #processIncomingZaloMessage}, i.e. with the same de-duplication, confirmation handling
     * and analysis as the webhook. A failure for one user does not stop the others.
     */
    public Flux<String> readMessages() {
        List<String> userIds = getScheduledUserIds();
        logger.info("Reading latest messages for {} scheduled user(s).", userIds.size());

        return Flux.fromIterable(userIds)
                .flatMap(userId -> processLatestMessage(userId)
                        .onErrorResume(e -> {
                            logger.warn("Reading messages for user {} failed: {}", userId, e.toString());
                            return Mono.empty();
                        }))
                .doOnComplete(() -> logger.info("Finished reading messages for all configured users."));
    }

    public Mono<String> processLatestMessage(String userId) {
        return getConversation(userId, 0, 1)
                // persistence below is blocking JPA, so leave the HTTP client's event-loop thread
                .publishOn(Schedulers.boundedElastic())
                .flatMap(response -> {
                    JsonArray dataArray = JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("data");
                    if (dataArray == null || dataArray.isEmpty()) {
                        return Mono.just("No messages for user " + userId);
                    }
                    JsonObject latest = dataArray.get(0).getAsJsonObject();
                    String messageContent = jsonString(latest, "message");
                    String messageId = jsonString(latest, "msg_id");
                    String fromId = jsonString(latest, "from_id");
                    if (messageContent == null || messageContent.isBlank()) {
                        return Mono.just("Latest message of user " + userId + " has no text; skipped");
                    }
                    if (fromId != null && fromId.equals(zaloConfig.getOaId())) {
                        return Mono.just("Latest message of user " + userId + " was sent by the OA; skipped");
                    }
                    return processIncomingZaloMessage(userId, messageContent, messageId)
                            .thenReturn("Handled latest message " + messageId + " of user " + userId);
                });
    }

    private static String jsonString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
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
}
