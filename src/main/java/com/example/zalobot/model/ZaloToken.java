package com.example.zalobot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "zalo_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ZaloToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "access_token", nullable = false, length = 2048)
    private String accessToken;

    @Column(name = "refresh_token", nullable = false, length = 2048)
    private String refreshToken;

    @Column(name = "expires_in", nullable = false)
    private Long expiresIn;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isAccessTokenExpired() {
        return LocalDateTime.now().isAfter(createdAt.plusSeconds(expiresIn));
    }
}