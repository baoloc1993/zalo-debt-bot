package com.example.zalobot.repository;

import com.example.zalobot.model.DebtRecord;
import com.example.zalobot.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface DebtRecordRepository extends JpaRepository<DebtRecord, Long> {
    @Query("SELECT COALESCE(SUM(d.amount), 0) FROM DebtRecord d WHERE d.appUser = :appUser")
    BigDecimal sumAmountByAppUser(AppUser appUser);
}
