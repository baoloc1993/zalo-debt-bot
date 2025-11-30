package com.example.zalobot.model;

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
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DebtRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zalo_message_id")
    private Long zaloMessageId; // Liên kết với tin nhắn Zalo gốc

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
}
