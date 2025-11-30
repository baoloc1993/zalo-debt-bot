package com.example.zalobot.repository;

import com.example.zalobot.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByName(String name);
    Optional<AppUser> findByZaloId(String zaloId);
}
