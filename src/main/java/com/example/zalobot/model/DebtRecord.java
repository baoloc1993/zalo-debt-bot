package com.example.zalobot.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Column(name = "debtor_name")
    private String debtorName;

    @Column(name = "amount")
    private String amount; // Sử dụng String để xử lý các định dạng tiền tệ khác nhau

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
