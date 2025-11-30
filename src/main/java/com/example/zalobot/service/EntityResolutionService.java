package com.example.zalobot.service;

import com.example.zalobot.model.AppUser;
import com.example.zalobot.repository.AppUserRepository;
import com.example.zalobot.repository.UserAliasRepository;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class EntityResolutionService {

    private static final int FUZZY_MATCH_THRESHOLD = 80;

    private final AppUserRepository appUserRepository;
    private final UserAliasRepository userAliasRepository;

    public EntityResolutionService(AppUserRepository appUserRepository, UserAliasRepository userAliasRepository) {
        this.appUserRepository = appUserRepository;
        this.userAliasRepository = userAliasRepository;
    }

    public Optional<AppUser> resolveUser(String name) {
        // Step 1: Exact match on AppUser name
        Optional<AppUser> exactMatch = appUserRepository.findByName(name);
        if (exactMatch.isPresent()) {
            return exactMatch;
        }

        // Step 2: Exact match on UserAlias
        return userAliasRepository.findByAlias(name)
                .map(userAlias -> Optional.of(userAlias.getAppUser()))
                .orElseGet(() -> {
                    // Step 3: Fuzzy match
                    List<AppUser> allUsers = appUserRepository.findAll();
                    return allUsers.stream()
                            .filter(user -> FuzzySearch.ratio(user.getName(), name) > FUZZY_MATCH_THRESHOLD)
                            .findFirst();
                });
    }
}
