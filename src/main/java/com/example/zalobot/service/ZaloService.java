package com.example.zalobot.service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

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
import com.example.zalobot.model.ZaloToken;
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
import com.example.zalobot.service.ZaloTokenService;
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
    private final ZaloTokenService zaloTokenService;
    private final Gson gson = new Gson();

    public ZaloService(ZaloConfig zaloConfig, WebClient.Builder webClientBuilder, GeminiApiClient geminiApiClient, ZaloMessageRepository zaloMessageRepository, DebtRecordRepository debtRecordRepository, AppUserRepository appUserRepository, PaymentRecordRepository paymentRecordRepository, EntityResolutionService entityResolutionService, PendingUserConfirmationRepository pendingUserConfirmationRepository, UserAliasRepository userAliasRepository, ZaloTokenService zaloTokenService) {
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
        this.zaloTokenService = zaloTokenService;
    }

    public ZaloConfig getZaloConfig() {
        return zaloConfig;
    }


    public Mono<String> getConversation(String userId, int offset, int count) {
        return getValidAccessToken()
                .flatMap(accessToken -> {
                    JsonObject data = new JsonObject();
                    data.addProperty("user_id", userId);
                    data.addProperty("offset", offset);
                    data.addProperty("count", count);
                    String dataJsonString = data.toString();
                    String encodedDataJsonString = java.net.URLEncoder.encode(dataJsonString, java.nio.charset.StandardCharsets.UTF_8);
                    String fullUriString = "https://openapi.zalo.me/v2.0" + "/oa/conversation?data=" + encodedDataJsonString;
                    logger.info("ZaloService - Constructed conversation URI string: {}", fullUriString);
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
        logger.info("ZaloService - Attempting to get a valid access token.");
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
        logger.info("ZaloService - Entering refreshAccessToken method.");
        logger.info("Refreshing Zalo access token...");

        return Mono.justOrEmpty(zaloTokenService.getLatestToken())
                .switchIfEmpty(Mono.error(new RuntimeException("Zalo refresh token is not configured in DB.")))
                .flatMap(token -> {
                    String refreshToken = token.getRefreshToken();
                    logger.info("ZaloService - Refresh token from DB: {}", refreshToken != null ? refreshToken.substring(0, Math.min(refreshToken.length(), 10)) + "..." : "null");
                    logger.info("ZaloService - App Secret from ZaloConfig: {}", zaloConfig.getAppSecret() != null ? zaloConfig.getAppSecret().substring(0, Math.min(zaloConfig.getAppSecret().length(), 10)) + "..." : "null");
                    String requestBody = "app_id=" + zaloConfig.getAppId() +
                            "&secret_key=" + zaloConfig.getAppSecret() +
                            "&refresh_token=" + refreshToken +
                            "&grant_type=refresh_token";
                    logger.info("ZaloService - Refresh token request body: {}", requestBody);

                    logger.info("ZaloService - Making WebClient call to Zalo token refresh API.");
                    return WebClient.builder().baseUrl("https://oauth.zalo.me/v4").build().post()
                            .uri("/access_token")
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("secret_key", zaloConfig.getAppSecret())
                            .bodyValue(requestBody)
                            .retrieve()
                            .bodyToMono(String.class)
                            .doOnNext(response -> logger.info("ZaloService - Received response from Zalo token refresh API: {}", response))
                            .flatMap(response -> {
                                logger.info("ZaloService - Attempting to parse Zalo token refresh response.");
                                try {
                                    JsonObject jsonResponse = JsonParser.parseString(response).getAsJsonObject();
                                    if (jsonResponse.has("access_token")) {
                                        String newAccessToken = jsonResponse.get("access_token").getAsString();
                                    String newRefreshToken = jsonResponse.get("refresh_token").getAsString();
                                    Long expiresIn = jsonResponse.get("expires_in").getAsLong();
                                    ZaloToken savedToken = zaloTokenService.saveToken(newAccessToken, newRefreshToken, expiresIn);
                                    logger.info("ZaloService - Successfully refreshed and saved new ZaloToken.");
                                    return Mono.just(savedToken);
                                    } else {
                                        logger.error("ZaloService - Zalo token refresh response does not contain 'access_token'. Response: {}", response);
                                        return Mono.error(new RuntimeException("Zalo token refresh response does not contain 'access_token'."));
                                    }
                                } catch (Exception e) {
                                    logger.error("ZaloService - Failed to parse Zalo token refresh response. Response: {}, Error: {}", response, e.getMessage());
                                    return Mono.error(new RuntimeException("Failed to parse Zalo token refresh response.", e));
                                }
                            })
                            .doOnError(error -> logger.error("ZaloService - Error refreshing Zalo access token: {}", error.getMessage()));
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
        return Mono.empty(); // Placeholder
    }

    private List<GeminiMessage> getConversationHistory(String senderId) {
        // Placeholder for conversation history retrieval
        return new CopyOnWriteArrayList<>();
    }

    public Mono<Void> readMessages(List<String> userIds) {
        return Flux.fromIterable(userIds)
                .flatMap(userId -> getConversation(userId, 0, 1) // Assuming default offset 0 and count 1
                        .doOnNext(response -> logger.info("Read messages for user {}: {}", userId, response))
                        .onErrorResume(e -> {
                            logger.error("Error reading messages for user {}: {}", userId, e.getMessage());
                            return Mono.empty();
                        }))
                .then();
    }

    public Mono<String> handleConfirmationResponse(String senderId, String response) {
        // Placeholder for handleConfirmationResponse
        return Mono.just("Handling confirmation response for user " + senderId);
    }

    public Mono<String> getProfile(String userId) {
        // Placeholder for getProfile
        return Mono.just("Getting profile for user " + userId);
    }

    public Mono<String> sendMessage(String userId, String message) {
        // Placeholder for sendMessage
        return Mono.just("Sending message to user " + userId);
    }

    public Mono<String> exchangeCodeForAccessToken(String code) {
        // Placeholder for exchangeCodeForAccessToken
        return Mono.just("Exchanging code for access token");
    }
}
