package com.example.zalobot.repository;

import com.example.zalobot.model.ZaloMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ZaloMessageRepository extends JpaRepository<ZaloMessage, Long> {
}
