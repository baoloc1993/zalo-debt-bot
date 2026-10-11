package io.github.baoloc1993.zalobot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "pending_user_confirmations")
@NoArgsConstructor
@AllArgsConstructor
public class PendingUserConfirmation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposed_app_user_name", nullable = false)
    private String proposedAppUserName;

    @Column(name = "zalo_message_id", nullable = false)
    private String zaloMessageId;

    @Column(name = "zalo_user_id", nullable = false)
    private String zaloUserId;

    @Column(name = "confirmation_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmationType confirmationType;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ConfirmationStatus status = ConfirmationStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "original_message_content", columnDefinition = "TEXT")
    private String originalMessageContent;

    @Column(name = "analysis_result_json", columnDefinition = "TEXT")
    private String analysisResultJson;

    /** What the bot is asking the user to confirm. */
    public enum ConfirmationType {
        CREATE_APP_USER
    }

    /** Lifecycle of a confirmation request: waiting for an answer, then accepted or rejected. */
    public enum ConfirmationStatus {
        PENDING,
        CONFIRMED,
        DENIED
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProposedAppUserName() {
        return proposedAppUserName;
    }

    public void setProposedAppUserName(String proposedAppUserName) {
        this.proposedAppUserName = proposedAppUserName;
    }

    public String getZaloMessageId() {
        return zaloMessageId;
    }

    public void setZaloMessageId(String zaloMessageId) {
        this.zaloMessageId = zaloMessageId;
    }

    public String getZaloUserId() {
        return zaloUserId;
    }

    public void setZaloUserId(String zaloUserId) {
        this.zaloUserId = zaloUserId;
    }

    public ConfirmationType getConfirmationType() {
        return confirmationType;
    }

    public void setConfirmationType(ConfirmationType confirmationType) {
        this.confirmationType = confirmationType;
    }

    public ConfirmationStatus getStatus() {
        return status;
    }

    public void setStatus(ConfirmationStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getOriginalMessageContent() {
        return originalMessageContent;
    }

    public void setOriginalMessageContent(String originalMessageContent) {
        this.originalMessageContent = originalMessageContent;
    }

    public String getAnalysisResultJson() {
        return analysisResultJson;
    }

    public void setAnalysisResultJson(String analysisResultJson) {
        this.analysisResultJson = analysisResultJson;
    }
}
