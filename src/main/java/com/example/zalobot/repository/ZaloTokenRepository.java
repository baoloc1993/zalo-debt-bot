package com.example.zalobot.repository;

import com.example.zalobot.model.ZaloToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ZaloTokenRepository extends JpaRepository<ZaloToken, Long> {
    Optional<ZaloToken> findTopByOrderByIdDesc();
}