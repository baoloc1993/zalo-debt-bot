package io.github.baoloc1993.zalobot.repository;

import io.github.baoloc1993.zalobot.model.AppUser;
import io.github.baoloc1993.zalobot.model.UserAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserAliasRepository extends JpaRepository<UserAlias, Long> {

    /**
     * Returns the owners of an alias as fully loaded entities. Selecting the {@link AppUser}
     * directly (instead of loading the {@link UserAlias} and calling its lazy
     * {@code getAppUser()}) means no lazy proxy escapes the repository transaction.
     */
    @Query("SELECT a.appUser FROM UserAlias a WHERE a.alias = :alias")
    List<AppUser> findAppUsersByAlias(@Param("alias") String alias);
}
