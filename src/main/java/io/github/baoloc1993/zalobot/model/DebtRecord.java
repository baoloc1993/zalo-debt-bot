package io.github.baoloc1993.zalobot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "debt_records")
@NoArgsConstructor
@AllArgsConstructor
public class DebtRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zalo_message_id")
    private String zaloMessageId; // Liên kết với tin nhắn Zalo gốc

    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "debtor_user_id", nullable = false)
    private AppUser debtorUser;

    @ToString.Exclude
    @ManyToOne
    @JoinColumn(name = "creditor_user_id", nullable = false)
    private AppUser creditorUser;

    @Column(name = "amount", precision = 19, scale = 4) // Sử dụng BigDecimal cho số tiền
    private BigDecimal amount;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "due_date")
    private LocalDate dueDate; // Ngày nợ

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getZaloMessageId() {
        return zaloMessageId;
    }

    public void setZaloMessageId(String zaloMessageId) {
        this.zaloMessageId = zaloMessageId;
    }

    public AppUser getDebtorUser() {
        return debtorUser;
    }

    public void setDebtorUser(AppUser debtorUser) {
        this.debtorUser = debtorUser;
    }

    public AppUser getCreditorUser() {
        return creditorUser;
    }

    public void setCreditorUser(AppUser creditorUser) {
        this.creditorUser = creditorUser;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
