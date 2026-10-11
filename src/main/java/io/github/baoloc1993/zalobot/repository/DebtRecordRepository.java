package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.DebtRecord;
import io.github.baoloc1993.zalobot.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface DebtRecordRepository extends JpaRepository<DebtRecord, Long> {
    @Query("SELECT COALESCE(SUM(d.amount), 0) FROM DebtRecord d WHERE d.creditorUser = :appUser")
    BigDecimal sumAmountWhereUserIsCreditor(AppUser appUser);
}
