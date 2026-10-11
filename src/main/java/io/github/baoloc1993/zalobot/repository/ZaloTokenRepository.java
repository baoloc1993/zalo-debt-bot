package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.ZaloToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ZaloTokenRepository extends JpaRepository<ZaloToken, Long> {
    Optional<ZaloToken> findTopByOrderByIdDesc();
}