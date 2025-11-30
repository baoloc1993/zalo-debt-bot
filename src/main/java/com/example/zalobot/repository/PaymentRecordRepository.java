package com.example.zalobot.repository;

import com.example.zalobot.model.PaymentRecord;
import com.example.zalobot.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecord p WHERE p.appUser = :appUser")
    BigDecimal sumAmountByAppUser(AppUser appUser);
}
