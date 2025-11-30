package com.example.zalobot.repository;

import com.example.zalobot.model.PendingUserConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PendingUserConfirmationRepository extends JpaRepository<PendingUserConfirmation, Long> {
    Optional<PendingUserConfirmation> findBySenderIdAndStatus(String senderId, PendingUserConfirmation.ConfirmationStatus status);
}
