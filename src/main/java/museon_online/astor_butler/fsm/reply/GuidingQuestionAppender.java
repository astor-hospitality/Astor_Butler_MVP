package museon_online.astor_butler.fsm.reply;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.i18n.GuestTexts;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Дописывает направляющий вопрос в конец ответа сценария (#101). Вызывается только для ответов, которые вернул
 * {@code ScenarioRouter}: служебные чаты, карточки персонала, очки и ошибки сюда не попадают.
 *
 * <p>Флаг {@code astor.dialogue.guiding-question.enabled} ({@code ASTOR_GUIDING_QUESTION_ENABLED}), по умолчанию выключен:
 * выключенный — ответ возвращается тем же объектом, байт в байт.
 *
 * <p>Правила: вопрос берётся из {@link GuidingQuestionPolicy}; не дописывается, если ответ уже заканчивается знаком
 * вопроса или тот же вопрос был предыдущим сообщением бота в этом чате ({@link GuidingQuestionMemory}); не трогаются
 * fallback-ответы, ответы на паузе Butler, запрос контакта и ответы не на русском.
 */
@Slf4j
@Component
public class GuidingQuestionAppender {

    public static final String METADATA_GUIDING_QUESTION = "guidingQuestion";
    static final String SEPARATOR = "\n\n";
    private static final String RUSSIAN = "ru";

    private final GuidingQuestionPolicy policy;
    private final GuidingQuestionMemory memory;
    private final boolean enabled;

    public GuidingQuestionAppender(
            GuidingQuestionPolicy policy,
            GuidingQuestionMemory memory,
            @Value("${astor.dialogue.guiding-question.enabled:false}") boolean enabled
    ) {
        this.policy = policy;
        this.memory = memory;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public OutgoingMessage append(IncomingMessage incoming, OutgoingMessage reply) {
        if (!enabled || reply == null || incoming == null || incoming.chatId() == null) {
            return reply;
        }
        if (!eligible(reply)) {
            return reply;
        }
        BotState state = stateOf(reply);
        Optional<String> question = policy.questionFor(scenarioIdOf(reply, state), state, reply.actions());
        Long chatId = incoming.chatId();
        if (question.isEmpty()) {
            forget(chatId);
            return reply;
        }
        String text = reply.text().stripTrailing();
        if (text.endsWith("?")) {
            // Сценарий уже спросил сам: запоминаем только тот же вопрос, чтобы не повторить его следующим ответом.
            if (text.endsWith(question.get())) {
                remember(chatId, question.get());
            } else {
                forget(chatId);
            }
            return reply;
        }
        if (last(chatId).filter(question.get()::equals).isPresent()) {
            return reply;
        }
        remember(chatId, question.get());
        return withQuestion(reply, text, question.get());
    }

    private boolean eligible(OutgoingMessage reply) {
        if (reply.text() == null || reply.text().isBlank() || reply.fallback() || reply.requestContact()) {
            return false;
        }
        List<String> actions = reply.actions() == null ? List.of() : reply.actions();
        if (actions.contains("BUTLER_PAUSED")) {
            return false;
        }
        Map<String, Object> metadata = reply.metadata() == null ? Map.of() : reply.metadata();
        Object language = metadata.get(GuestTexts.METADATA_REPLY_LANGUAGE);
        return language == null || RUSSIAN.equalsIgnoreCase(String.valueOf(language).trim());
    }

    private BotState stateOf(OutgoingMessage reply) {
        if (reply.nextState() == null || reply.nextState().isBlank()) {
            return null;
        }
        try {
            return BotState.valueOf(reply.nextState().trim()).canonical();
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String scenarioIdOf(OutgoingMessage reply, BotState state) {
        Map<String, Object> metadata = reply.metadata() == null ? Map.of() : reply.metadata();
        Object tagged = metadata.get("scenario");
        if (tagged != null && !String.valueOf(tagged).isBlank()) {
            return String.valueOf(tagged).trim();
        }
        return policy.ownerOf(state).orElse(null);
    }

    private OutgoingMessage withQuestion(OutgoingMessage reply, String text, String question) {
        return new OutgoingMessage(
                reply.channel(),
                reply.externalUserId(),
                reply.chatId(),
                text + SEPARATOR + question,
                reply.nextState(),
                reply.html(),
                reply.requestContact(),
                reply.removeKeyboard(),
                reply.fallback(),
                reply.adminAlert(),
                reply.actions(),
                reply.metadata(),
                reply.createdAt()
        ).withMetadata(Map.of(METADATA_GUIDING_QUESTION, question));
    }

    private Optional<String> last(Long chatId) {
        try {
            return memory.last(chatId);
        } catch (RuntimeException ex) {
            log.warn("Guiding question memory is unavailable for chat {}: {}", chatId, ex.toString());
            return Optional.empty();
        }
    }

    private void remember(Long chatId, String question) {
        try {
            memory.remember(chatId, question);
        } catch (RuntimeException ex) {
            log.warn("Guiding question was not remembered for chat {}: {}", chatId, ex.toString());
        }
    }

    private void forget(Long chatId) {
        try {
            memory.forget(chatId);
        } catch (RuntimeException ex) {
            log.warn("Guiding question memory was not cleared for chat {}: {}", chatId, ex.toString());
        }
    }
}
