package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.AppUser;
import io.github.baoloc1993.zalobot.model.UserAlias;
import io.github.baoloc1993.zalobot.model.ZaloMessage;
import io.github.baoloc1993.zalobot.repository.AppUserRepository;
import io.github.baoloc1993.zalobot.repository.UserAliasRepository;
import io.github.baoloc1993.zalobot.repository.ZaloMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against an in-memory H2 database (MySQL mode) with the real JPA mappings. The tests are
 * deliberately NOT transactional: in production the service calls repositories without an
 * enclosing transaction, so a lazy association loaded in one repository call is detached afterwards.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:zalobot-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import({EntityResolutionService.class, InboundMessageRegistry.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PersistenceIntegrationTest {

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private UserAliasRepository userAliasRepository;
    @Autowired
    private ZaloMessageRepository zaloMessageRepository;
    @Autowired
    private EntityResolutionService entityResolutionService;
    @Autowired
    private InboundMessageRegistry inboundMessageRegistry;

    @AfterEach
    void cleanUp() {
        userAliasRepository.deleteAll();
        appUserRepository.deleteAll();
        zaloMessageRepository.deleteAll();
    }

    private AppUser userWithAlias(String name, String alias) {
        AppUser user = new AppUser();
        user.setName(name);
        user = appUserRepository.save(user);
        UserAlias userAlias = new UserAlias();
        userAlias.setAlias(alias);
        userAlias.setAppUser(user);
        userAliasRepository.save(userAlias);
        return user;
    }

    @Test
    void aliasMatch_resolvesTheOwnerWithoutLazyInitializationException() {
        AppUser owner = userWithAlias("Nguyen Van Binh", "anh Binh");

        Optional<AppUser> resolved = entityResolutionService.resolveAppUser("anh Binh")
                .block(Duration.ofSeconds(10));

        assertTrue(resolved.isPresent());
        assertEquals(owner.getId(), resolved.get().getId());
        // reading state of the returned entity after the repository call has returned must not throw
        assertEquals("Nguyen Van Binh", resolved.get().getName());
    }

    @Test
    void exactNameMatch_isPreferredOverAlias() {
        userWithAlias("Lan", "Mai");
        AppUser mai = new AppUser();
        mai.setName("Mai");
        mai = appUserRepository.save(mai);

        Optional<AppUser> resolved = entityResolutionService.resolveAppUser("Mai").block(Duration.ofSeconds(10));

        assertTrue(resolved.isPresent());
        assertEquals(mai.getId(), resolved.get().getId());
    }

    @Test
    void unknownName_resolvesToEmpty() {
        userWithAlias("Lan", "Lan Anh");

        Optional<AppUser> resolved = entityResolutionService.resolveAppUser("Zzzzzz Qqqqq").block(Duration.ofSeconds(10));

        assertFalse(resolved.isPresent());
    }

    @Test
    void zaloMessageId_isUniqueInTheDatabase() {
        ZaloMessage first = inbound("msg-1");
        ZaloMessage second = inbound("msg-1");
        zaloMessageRepository.saveAndFlush(first);

        assertThrows(DataIntegrityViolationException.class, () -> zaloMessageRepository.saveAndFlush(second));
    }

    @Test
    void registry_registersAMessageOnlyOnce() {
        assertTrue(inboundMessageRegistry.tryRegister(inbound("msg-2")));
        assertFalse(inboundMessageRegistry.tryRegister(inbound("msg-2")));
        assertEquals(1, zaloMessageRepository.count());
    }

    private ZaloMessage inbound(String zaloMessageId) {
        ZaloMessage m = new ZaloMessage();
        m.setSenderId("user-1");
        m.setReceiverId("oa-1");
        m.setMessageContent("hello");
        m.setMessageType("text");
        m.setDirection(ZaloMessage.Direction.inbound);
        m.setZaloMessageId(zaloMessageId);
        return m;
    }
}
