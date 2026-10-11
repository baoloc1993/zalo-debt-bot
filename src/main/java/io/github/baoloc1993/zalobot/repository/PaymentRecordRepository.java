package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.PaymentRecord;
import io.github.baoloc1993.zalobot.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PaymentRecord p WHERE p.appUser = :appUser")
    BigDecimal sumAmountByAppUser(AppUser appUser);
}
