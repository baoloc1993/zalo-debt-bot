package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.ZaloMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ZaloMessageRepository extends JpaRepository<ZaloMessage, Long> {

    boolean existsByZaloMessageId(String zaloMessageId);
}
