package com.example.zalobot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "pending_user_confirmations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PendingUserConfirmation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unresolved_name", nullable = false)
    private String unresolvedName;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "sender_id", nullable = false)
    private String senderId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmationStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "original_message", columnDefinition = "TEXT")
    private String originalMessage;

    public enum ConfirmationStatus {
        PENDING,
        CONFIRMED,
        DENIED
    }
}
