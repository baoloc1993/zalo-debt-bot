package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.config.ZaloConfig;
import io.github.baoloc1993.zalobot.model.AppUser;
import io.github.baoloc1993.zalobot.model.DebtRecord;
import io.github.baoloc1993.zalobot.model.PendingUserConfirmation;
import io.github.baoloc1993.zalobot.model.TransactionAnalysisResult;
import io.github.baoloc1993.zalobot.model.ZaloMessage;
import io.github.baoloc1993.zalobot.repository.AppUserRepository;
import io.github.baoloc1993.zalobot.repository.DebtRecordRepository;
import io.github.baoloc1993.zalobot.repository.PaymentRecordRepository;
import io.github.baoloc1993.zalobot.repository.PendingUserConfirmationRepository;
import io.github.baoloc1993.zalobot.repository.UserAliasRepository;
import io.github.baoloc1993.zalobot.repository.ZaloMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The same Zalo message id must be processed once, whether it arrives twice through the webhook,
 * through the poller, or from two overlapping deliveries.
 */
class MessageDeduplicationTest {

    private static final String SENDER = "zalo-user-1";
    private static final String TEXT = "Tôi nợ Lan 50k tiền ăn trưa";

    private ZaloMessageRepository messageRepository;
    private InboundMessageRegistry registry;
    private GeminiApiClient geminiApiClient;
    private DebtRecordRepository debtRecordRepository;
    private PendingUserConfirmationRepository pendingRepository;
    private EntityResolutionService entityResolutionService;
    private ZaloConfig config;
    private ZaloService service;
    private final Set<String> storedIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        // in-memory stand-in for the unique zalo_message_id column
        messageRepository = mock(ZaloMessageRepository.class);
        when(messageRepository.existsByZaloMessageId(anyString())).thenAnswer(inv -> storedIds.contains(inv.<String>getArgument(0)));
        when(messageRepository.saveAndFlush(any(ZaloMessage.class))).thenAnswer(inv -> {
            ZaloMessage m = inv.getArgument(0);
            if (!storedIds.add(m.getZaloMessageId())) {
                throw new DataIntegrityViolationException("duplicate zalo_message_id");
            }
            return m;
        });
        registry = new InboundMessageRegistry(messageRepository);

        geminiApiClient = mock(GeminiApiClient.class);
        debtRecordRepository = mock(DebtRecordRepository.class);
        pendingRepository = mock(PendingUserConfirmationRepository.class);
        entityResolutionService = mock(EntityResolutionService.class);
        config = mock(ZaloConfig.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);

        service = spy(new ZaloService(config, WebClient.builder(), geminiApiClient, messageRepository,
                debtRecordRepository, appUserRepository, mock(PaymentRecordRepository.class),
                entityResolutionService, pendingRepository, mock(UserAliasRepository.class),
                mock(ZaloTokenService.class), registry));
        doReturn(Mono.just("sent")).when(service).sendMessage(anyString(), anyString());

        AppUser sender = new AppUser();
        sender.setName("Sender");
        when(appUserRepository.findByZaloId(SENDER)).thenReturn(Optional.of(sender));
        when(pendingRepository.save(any(PendingUserConfirmation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pendingRepository.findFirstByZaloUserIdAndStatusOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());
        when(debtRecordRepository.save(any(DebtRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TransactionAnalysisResult debtAnalysis() {
        TransactionAnalysisResult r = new TransactionAnalysisResult();
        r.setTransactionRelated(true);
        r.setTransactionType("debt");
        r.setDebtDirection("USER_OWES_OTHER");
        r.setAppUserName("Lan");
        r.setAmount(50000.0);
        return r;
    }

    private void lanIsKnown() {
        AppUser lan = new AppUser();
        lan.setName("Lan");
        when(entityResolutionService.resolveAppUser("Lan")).thenReturn(Mono.just(Optional.of(lan)));
    }

    @Test
    void sameMessageIdProcessedTwice_createsOneDebtRecord() {
        lanIsKnown();
        when(geminiApiClient.analyzeMessageForDebt(TEXT)).thenReturn(Mono.just(debtAnalysis()));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();
        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();

        verify(geminiApiClient, times(1)).analyzeMessageForDebt(TEXT);
        verify(debtRecordRepository, times(1)).save(any());
    }

    @Test
    void sameMessageIdProcessedTwice_asksTheConfirmationQuestionOnce() {
        when(entityResolutionService.resolveAppUser("Lan")).thenReturn(Mono.just(Optional.empty()));
        when(geminiApiClient.analyzeMessageForDebt(TEXT)).thenReturn(Mono.just(debtAnalysis()));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();
        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();

        verify(pendingRepository, times(1)).save(any());
        verify(service, times(1)).sendMessage(anyString(), anyString());
    }

    @Test
    void differentMessageIds_areBothProcessed() {
        lanIsKnown();
        when(geminiApiClient.analyzeMessageForDebt(TEXT)).thenReturn(Mono.just(debtAnalysis()));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();
        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-2")).verifyComplete();

        verify(debtRecordRepository, times(2)).save(any());
    }

    @Test
    void overlappingDeliveries_loserOfTheInsertRaceIsSkipped() {
        // Both deliveries pass the exists() check before either has inserted
        when(messageRepository.existsByZaloMessageId("msg-1")).thenReturn(false);
        lanIsKnown();
        when(geminiApiClient.analyzeMessageForDebt(TEXT)).thenReturn(Mono.just(debtAnalysis()));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();
        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();

        // the second insert hit the unique constraint, which is treated as "already processed"
        verify(messageRepository, times(2)).saveAndFlush(any());
        verify(debtRecordRepository, times(1)).save(any());
    }

    @Test
    void registry_reportsDuplicateOnConstraintViolation() {
        ZaloMessage first = new ZaloMessage();
        first.setZaloMessageId("msg-9");
        ZaloMessage second = new ZaloMessage();
        second.setZaloMessageId("msg-9");
        // make exists() lie so the unique-violation path is the only thing that can stop the second insert
        when(messageRepository.existsByZaloMessageId("msg-9")).thenReturn(false);

        assertTrue(registry.tryRegister(first));
        assertFalse(registry.tryRegister(second));
    }

    @Test
    void messageThatFailsAnalysis_isNotRetried() {
        when(geminiApiClient.analyzeMessageForDebt(TEXT)).thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();
        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();

        verify(geminiApiClient, times(1)).analyzeMessageForDebt(TEXT);
        verify(debtRecordRepository, never()).save(any());
    }

    @Test
    void pollerAndWebhookShareTheSameDeduplication() {
        lanIsKnown();
        when(geminiApiClient.analyzeMessageForDebt(TEXT)).thenReturn(Mono.just(debtAnalysis()));
        String conversation = "{\"data\":[{\"msg_id\":\"msg-1\",\"message\":\"" + TEXT + "\",\"from_id\":\"" + SENDER + "\"}]}";
        doReturn(Mono.just(conversation)).when(service).getConversation(SENDER, 0, 1);
        when(config.getScheduledUsers()).thenReturn(SENDER);

        // webhook delivers it first, then the poller sees the same latest message on two ticks
        StepVerifier.create(service.processIncomingZaloMessage(SENDER, TEXT, "msg-1")).verifyComplete();
        StepVerifier.create(service.readMessages()).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.readMessages()).expectNextCount(1).verifyComplete();

        verify(debtRecordRepository, times(1)).save(any());
    }

    @Test
    void poller_twoTicksOnTheSameLatestMessage_createOneRecord() {
        lanIsKnown();
        when(geminiApiClient.analyzeMessageForDebt(TEXT)).thenReturn(Mono.just(debtAnalysis()));
        String conversation = "{\"data\":[{\"msg_id\":\"msg-7\",\"message\":\"" + TEXT + "\",\"from_id\":\"" + SENDER + "\"}]}";
        doReturn(Mono.just(conversation)).when(service).getConversation(SENDER, 0, 1);
        when(config.getScheduledUsers()).thenReturn(SENDER);

        StepVerifier.create(service.readMessages()).expectNextCount(1).verifyComplete();
        StepVerifier.create(service.readMessages()).expectNextCount(1).verifyComplete();

        verify(debtRecordRepository, times(1)).save(any());
    }

    @Test
    void poller_skipsMessagesSentByTheOfficialAccount() {
        when(config.getOaId()).thenReturn("oa-1");
        when(config.getScheduledUsers()).thenReturn(SENDER);
        String conversation = "{\"data\":[{\"msg_id\":\"msg-8\",\"message\":\"Vui lòng trả lời\",\"from_id\":\"oa-1\"}]}";
        doReturn(Mono.just(conversation)).when(service).getConversation(SENDER, 0, 1);

        StepVerifier.create(service.readMessages()).expectNextCount(1).verifyComplete();

        verify(geminiApiClient, never()).analyzeMessageForDebt(anyString());
    }
}
