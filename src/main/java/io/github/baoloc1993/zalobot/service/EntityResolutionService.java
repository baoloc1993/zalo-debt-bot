package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.AppUser;
import io.github.baoloc1993.zalobot.repository.AppUserRepository;
import io.github.baoloc1993.zalobot.repository.UserAliasRepository;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a name mentioned in a message to a known {@link AppUser}: exact name, then alias,
 * then fuzzy match.
 */
@Service
public class EntityResolutionService {

    private static final Logger logger = LoggerFactory.getLogger(EntityResolutionService.class);

    private static final int FUZZY_MATCH_THRESHOLD = 80;

    private final AppUserRepository appUserRepository;
    private final UserAliasRepository userAliasRepository;

    public EntityResolutionService(AppUserRepository appUserRepository, UserAliasRepository userAliasRepository) {
        this.appUserRepository = appUserRepository;
        this.userAliasRepository = userAliasRepository;
    }

    /** Never empty: emits {@code Optional.empty()} when nobody matches. Blocking lookups run on boundedElastic. */
    public Mono<Optional<AppUser>> resolveAppUser(String name) {
        return Mono.fromCallable(() -> resolveBlocking(name)).subscribeOn(Schedulers.boundedElastic());
    }

    private Optional<AppUser> resolveBlocking(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        Optional<AppUser> byName = appUserRepository.findByName(name);
        if (byName.isPresent()) {
            logger.info("Resolved a person by exact name match.");
            return byName;
        }

        List<AppUser> byAlias = userAliasRepository.findAppUsersByAlias(name);
        if (!byAlias.isEmpty()) {
            logger.info("Resolved a person by alias.");
            return Optional.of(byAlias.get(0));
        }

        Optional<AppUser> fuzzy = appUserRepository.findAll().stream()
                .filter(user -> FuzzySearch.ratio(user.getName(), name) > FUZZY_MATCH_THRESHOLD)
                .findFirst();
        logger.info(fuzzy.isPresent() ? "Resolved a person by fuzzy match." : "No person matched the name.");
        return fuzzy;
    }
}
