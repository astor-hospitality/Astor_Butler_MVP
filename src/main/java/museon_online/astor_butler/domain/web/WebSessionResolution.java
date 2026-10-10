package museon_online.astor_butler.domain.web;

import java.util.UUID;

/** A resolved browser session; {@code created} is true when this request inserted the row (first message). */
public record WebSessionResolution(
        UUID id,
        String sessionId,
        String externalUserId,
        Long chatId,
        boolean created
) {
    public WebSessionResolution(UUID id, String sessionId, String externalUserId, Long chatId) {
        this(id, sessionId, externalUserId, chatId, false);
    }
}
