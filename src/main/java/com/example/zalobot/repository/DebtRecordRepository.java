package com.example.zalobot.repository;

import com.example.zalobot.model.DebtRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DebtRecordRepository extends JpaRepository<DebtRecord, Long> {
}
