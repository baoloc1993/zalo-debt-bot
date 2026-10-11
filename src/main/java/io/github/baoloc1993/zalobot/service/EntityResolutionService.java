package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.AppUser;
import io.github.baoloc1993.zalobot.repository.AppUserRepository;
import io.github.baoloc1993.zalobot.repository.UserAliasRepository;
import me.xdrop.fuzzywuzzy.FuzzySearch;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public Mono<Optional<AppUser>> resolveAppUser(String name) {
        logger.info("Attempting to resolve AppUser for name: {}", name);
        // Step 1: Exact match on AppUser name
        return Mono.justOrEmpty(appUserRepository.findByName(name))
                .map(Optional::of)
                .doOnNext(appUserOpt -> appUserOpt.ifPresent(appUser -> logger.info("Found AppUser by exact name match: {}", appUser.getName())))
                .switchIfEmpty(Mono.defer(() -> {
                    logger.info("No exact AppUser name match for {}. Checking aliases.", name);
                    // Step 2: Exact match on UserAlias
                    return Mono.justOrEmpty(userAliasRepository.findByAlias(name))
                            .map(userAlias -> Optional.of(userAlias.getAppUser()))
                            .doOnNext(appUserOpt -> appUserOpt.ifPresent(appUser -> logger.info("Found AppUser by alias: {}", appUser.getName())))
                            .switchIfEmpty(Mono.defer(() -> {
                                logger.info("No alias match for {}. Attempting fuzzy match.", name);
                                // Step 3: Fuzzy match
                                return Mono.fromCallable(() -> {
                                    List<AppUser> allUsers = appUserRepository.findAll();
                                    return allUsers.stream()
                                                    .filter(user -> FuzzySearch.ratio(user.getName(), name) > FUZZY_MATCH_THRESHOLD)
                                                    .findFirst();
                                        }).flatMap(Mono::justOrEmpty)
                                                .map(Optional::of)
                                                .doOnNext(appUserOpt -> appUserOpt.ifPresent(appUser -> logger.info("Found AppUser by fuzzy match: {}", appUser.getName())))
                                                .switchIfEmpty(Mono.defer(() -> {
                                                    logger.info("No fuzzy match found for {}.", name);
                                                    return Mono.just(Optional.empty());
                                                }));
                            }));
                }));
    }
}
