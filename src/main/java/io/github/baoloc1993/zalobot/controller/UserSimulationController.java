package io.github.baoloc1993.zalobot.controller;

import io.github.baoloc1993.zalobot.service.UserSimulationService;
import io.github.baoloc1993.zalobot.repository.AppUserRepository;
import io.github.baoloc1993.zalobot.repository.DebtRecordRepository;
import io.github.baoloc1993.zalobot.repository.PaymentRecordRepository;
import io.github.baoloc1993.zalobot.repository.PendingUserConfirmationRepository;
import io.github.baoloc1993.zalobot.repository.UserAliasRepository;
import io.github.baoloc1993.zalobot.repository.ZaloMessageRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/simulate")
public class UserSimulationController {

    private final UserSimulationService userSimulationService;

    private final AppUserRepository appUserRepository;
    private final UserAliasRepository userAliasRepository;
    private final DebtRecordRepository debtRecordRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final PendingUserConfirmationRepository pendingUserConfirmationRepository;
    private final ZaloMessageRepository zaloMessageRepository;

    public UserSimulationController(UserSimulationService userSimulationService, AppUserRepository appUserRepository, UserAliasRepository userAliasRepository, DebtRecordRepository debtRecordRepository, PaymentRecordRepository paymentRecordRepository, PendingUserConfirmationRepository pendingUserConfirmationRepository, ZaloMessageRepository zaloMessageRepository) {
        this.userSimulationService = userSimulationService;
        this.appUserRepository = appUserRepository;
        this.userAliasRepository = userAliasRepository;
        this.debtRecordRepository = debtRecordRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.pendingUserConfirmationRepository = pendingUserConfirmationRepository;
        this.zaloMessageRepository = zaloMessageRepository;
    }

    @PostMapping("/message")
    public Mono<String> simulateMessage(@RequestParam String senderId, @RequestParam String messageText) {
        return userSimulationService.simulateIncomingMessage(senderId, messageText)
                .thenReturn("Simulation of incoming message sent successfully.");
    }



    @PostMapping("/clear-db")
    public Mono<String> clearDatabase() {
        debtRecordRepository.deleteAll();
        paymentRecordRepository.deleteAll();
        pendingUserConfirmationRepository.deleteAll();
        userAliasRepository.deleteAll();
        appUserRepository.deleteAll();
        zaloMessageRepository.deleteAll();
        return Mono.just("Database cleared successfully.");
    }
}