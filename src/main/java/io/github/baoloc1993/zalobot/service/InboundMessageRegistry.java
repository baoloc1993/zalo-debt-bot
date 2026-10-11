package io.github.baoloc1993.zalobot.service;

import io.github.baoloc1993.zalobot.model.ZaloMessage;
import io.github.baoloc1993.zalobot.repository.ZaloMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Records inbound Zalo messages exactly once, keyed by the Zalo message id.
 * <p>
 * This is the single de-duplication point shared by the webhook, the scheduled poller and the dev
 * simulator. {@code zalo_messages.zalo_message_id} has a unique constraint, so even when two
 * deliveries race past the {@code exists} check, only one insert succeeds and the loser is
 * reported as "already registered".
 * <p>
 * Intentionally not {@code @Transactional}: each repository call commits on its own, so a
 * constraint violation cannot poison a surrounding transaction.
 */
@Component
public class InboundMessageRegistry {

    private static final Logger logger = LoggerFactory.getLogger(InboundMessageRegistry.class);

    private final ZaloMessageRepository zaloMessageRepository;

    public InboundMessageRegistry(ZaloMessageRepository zaloMessageRepository) {
        this.zaloMessageRepository = zaloMessageRepository;
    }

    /**
     * @return {@code true} if the message was stored now (the caller should process it),
     *         {@code false} if a message with the same Zalo id was already stored
     */
    public boolean tryRegister(ZaloMessage message) {
        String zaloMessageId = message.getZaloMessageId();
        if (zaloMessageId == null) {
            // No id to de-duplicate on; always process
            zaloMessageRepository.save(message);
            return true;
        }
        if (zaloMessageRepository.existsByZaloMessageId(zaloMessageId)) {
            return false;
        }
        try {
            zaloMessageRepository.saveAndFlush(message);
            return true;
        } catch (DataIntegrityViolationException e) {
            logger.debug("Zalo message {} was registered concurrently.", zaloMessageId);
            return false;
        }
    }
}
