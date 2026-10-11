package io.github.baoloc1993.zalobot.service;

import com.google.gson.Gson;
import io.github.baoloc1993.zalobot.config.ZaloConfig;
import io.github.baoloc1993.zalobot.model.AppUser;
import io.github.baoloc1993.zalobot.model.DebtRecord;
import io.github.baoloc1993.zalobot.model.PendingUserConfirmation;
import io.github.baoloc1993.zalobot.model.PendingUserConfirmation.ConfirmationStatus;
import io.github.baoloc1993.zalobot.model.TransactionAnalysisResult;
import io.github.baoloc1993.zalobot.repository.AppUserRepository;
import io.github.baoloc1993.zalobot.repository.DebtRecordRepository;
import io.github.baoloc1993.zalobot.repository.PaymentRecordRepository;
import io.github.baoloc1993.zalobot.repository.PendingUserConfirmationRepository;
import io.github.baoloc1993.zalobot.repository.UserAliasRepository;
import io.github.baoloc1993.zalobot.repository.ZaloMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Service-level tests of the debt message -> confirmation question -> yes/no reply flow.
 * Repositories and the Gemini client are mocked; outgoing Zalo messages are stubbed so no
 * network access is needed.
 */
class ZaloServiceConfirmationFlowTest {

    private static final String SENDER = "zalo-user-1";
    private static final String MESSAGE_ID = "msg-1";
    private static final String ORIGINAL_TEXT = "Tôi nợ Lan 50k tiền ăn trưa";

    private final Gson gson = new Gson();

    private GeminiApiClient geminiApiClient;
    private DebtRecordRepository debtRecordRepository;
    private AppUserRepository appUserRepository;
    private PendingUserConfirmationRepository pendingRepository;
    private EntityResolutionService entityResolutionService;
    private ZaloService service;
    private AppUser sender;

    @BeforeEach
    void setUp() {
        geminiApiClient = mock(GeminiApiClient.class);
        debtRecordRepository = mock(DebtRecordRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        pendingRepository = mock(PendingUserConfirmationRepository.class);
        entityResolutionService = mock(EntityResolutionService.class);
        ZaloConfig config = mock(ZaloConfig.class);
        InboundMessageRegistry inboundMessageRegistry = mock(InboundMessageRegistry.class);
        when(inboundMessageRegistry.tryRegister(any())).thenReturn(true);

        service = spy(new ZaloService(config, WebClient.builder(), geminiApiClient,
                mock(ZaloMessageRepository.class), debtRecordRepository, appUserRepository,
                mock(PaymentRecordRepository.class), entityResolutionService, pendingRepository,
                mock(UserAliasRepository.class), mock(ZaloTokenService.class), inboundMessageRegistry));
        doReturn(Mono.just("sent")).when(service).sendMessage(anyString(), anyString());

        sender = new AppUser();
        sender.setName("Sender");
        sender.setZaloId(SENDER);
        when(appUserRepository.findByZaloId(SENDER)).thenReturn(Optional.of(sender));
        when(appUserRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pendingRepository.save(any(PendingUserConfirmation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(debtRecordRepository.save(any(DebtRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(pendingRepository.findFirstByZaloUserIdAndStatusOrderByCreatedAtDesc(SENDER, ConfirmationStatus.PENDING))
                .thenReturn(Optional.empty());
    }

    private TransactionAnalysisResult debtAnalysis() {
        TransactionAnalysisResult r = new TransactionAnalysisResult();
        r.setTransactionRelated(true);
        r.setTransactionType("debt");
        r.setDebtDirection("USER_OWES_OTHER");
        r.setAppUserName("Lan");
        r.setAmount(50000.0);
        r.setReason("tiền ăn trưa");
        r.setZaloMessageId(MESSAGE_ID);
        return r;
    }

    private PendingUserConfirmation openConfirmation() {
        PendingUserConfirmation p = new PendingUserConfirmation();
        p.setZaloUserId(SENDER);
        p.setZaloMessageId(MESSAGE_ID);
        p.setProposedAppUserName("Lan");
        p.setOriginalMessageContent(ORIGINAL_TEXT);
        p.setConfirmationType(PendingUserConfirmation.ConfirmationType.CREATE_APP_USER);
        p.setStatus(ConfirmationStatus.PENDING);
        p.setAnalysisResultJson(gson.toJson(debtAnalysis()));
        when(pendingRepository.findFirstByZaloUserIdAndStatusOrderByCreatedAtDesc(SENDER, ConfirmationStatus.PENDING))
                .thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void debtMessageWithUnknownPerson_savesPendingConfirmationAndAsksQuestion() {
        when(geminiApiClient.analyzeMessageForDebt(ORIGINAL_TEXT)).thenReturn(Mono.just(debtAnalysis()));
        when(entityResolutionService.resolveAppUser("Lan")).thenReturn(Mono.just(Optional.empty()));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, ORIGINAL_TEXT, MESSAGE_ID))
                .verifyComplete();

        ArgumentCaptor<PendingUserConfirmation> saved = ArgumentCaptor.forClass(PendingUserConfirmation.class);
        verify(pendingRepository).save(saved.capture());
        assertEquals(ConfirmationStatus.PENDING, saved.getValue().getStatus());
        assertEquals(PendingUserConfirmation.ConfirmationType.CREATE_APP_USER, saved.getValue().getConfirmationType());
        assertEquals("Lan", saved.getValue().getProposedAppUserName());
        assertEquals(MESSAGE_ID, saved.getValue().getZaloMessageId());
        verify(service).sendMessage(eq(SENDER), contains("Lan"));
        verify(debtRecordRepository, never()).save(any());
    }

    @Test
    void debtMessageWithKnownPerson_recordsDebtWithoutAsking() {
        AppUser lan = new AppUser();
        lan.setName("Lan");
        when(geminiApiClient.analyzeMessageForDebt(ORIGINAL_TEXT)).thenReturn(Mono.just(debtAnalysis()));
        when(entityResolutionService.resolveAppUser("Lan")).thenReturn(Mono.just(Optional.of(lan)));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, ORIGINAL_TEXT, MESSAGE_ID))
                .verifyComplete();

        verify(pendingRepository, never()).save(any());
        ArgumentCaptor<DebtRecord> debt = ArgumentCaptor.forClass(DebtRecord.class);
        verify(debtRecordRepository).save(debt.capture());
        assertSame(sender, debt.getValue().getDebtorUser());
        assertSame(lan, debt.getValue().getCreditorUser());
    }

    @Test
    void replyYes_createsUserRecordsDebtAndMarksConfirmed() {
        PendingUserConfirmation pending = openConfirmation();
        when(appUserRepository.findByName("Lan")).thenReturn(Optional.empty());
        AppUser lan = new AppUser();
        lan.setName("Lan");
        when(entityResolutionService.resolveAppUser("Lan")).thenReturn(Mono.just(Optional.of(lan)));

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, "Có", "msg-2"))
                .verifyComplete();

        assertEquals(ConfirmationStatus.CONFIRMED, pending.getStatus());
        ArgumentCaptor<AppUser> created = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(created.capture());
        assertEquals("Lan", created.getValue().getName());

        ArgumentCaptor<DebtRecord> debt = ArgumentCaptor.forClass(DebtRecord.class);
        verify(debtRecordRepository).save(debt.capture());
        assertSame(sender, debt.getValue().getDebtorUser());
        assertSame(lan, debt.getValue().getCreditorUser());
        assertEquals(MESSAGE_ID, debt.getValue().getZaloMessageId());
        assertEquals(0, debt.getValue().getAmount().compareTo(new java.math.BigDecimal("50000")));
        // the reply is an answer, not a new message to analyse
        verify(geminiApiClient, never()).analyzeMessageForDebt(anyString());
    }

    @Test
    void replyNo_marksDeniedAndRecordsNothing() {
        PendingUserConfirmation pending = openConfirmation();

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, "Không", "msg-2"))
                .verifyComplete();

        assertEquals(ConfirmationStatus.DENIED, pending.getStatus());
        verify(appUserRepository, never()).save(any());
        verify(debtRecordRepository, never()).save(any());
        verify(geminiApiClient, never()).analyzeMessageForDebt(anyString());
        verify(service).sendMessage(eq(SENDER), contains("hủy"));
    }

    @Test
    void invalidReply_keepsConfirmationPendingAndAsksAgain() {
        PendingUserConfirmation pending = openConfirmation();

        StepVerifier.create(service.processIncomingZaloMessage(SENDER, "có thể", "msg-2"))
                .verifyComplete();

        assertEquals(ConfirmationStatus.PENDING, pending.getStatus());
        verify(appUserRepository, never()).save(any());
        verify(debtRecordRepository, never()).save(any());
        verify(geminiApiClient, never()).analyzeMessageForDebt(anyString());
        verify(service).sendMessage(eq(SENDER), contains("Có"));
    }
}
