package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.PendingUserConfirmation;
import io.github.baoloc1993.zalobot.model.PendingUserConfirmation.ConfirmationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PendingUserConfirmationRepository extends JpaRepository<PendingUserConfirmation, Long> {

    /** Most recent confirmation request of the given user that is in the given status. */
    Optional<PendingUserConfirmation> findFirstByZaloUserIdAndStatusOrderByCreatedAtDesc(String zaloUserId, ConfirmationStatus status);
}
