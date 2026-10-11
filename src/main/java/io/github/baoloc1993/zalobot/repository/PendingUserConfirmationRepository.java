package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.PendingUserConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PendingUserConfirmationRepository extends JpaRepository<PendingUserConfirmation, Long> {
    Optional<PendingUserConfirmation> findByZaloUserIdAndZaloMessageId(String zaloUserId, String zaloMessageId);
    Optional<PendingUserConfirmation> findByZaloUserIdAndConfirmationType(String zaloUserId, PendingUserConfirmation.ConfirmationType confirmationType);
}
