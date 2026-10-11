package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.UserAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAliasRepository extends JpaRepository<UserAlias, Long> {
    Optional<UserAlias> findByAlias(String alias);
}
