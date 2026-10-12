package museon_online.astor_butler.fsm.reply;

import java.util.Optional;

/**
 * Последний направляющий вопрос, который бот дописал гостю в этом чате (#101): тот же вопрос подряд не повторяется.
 * Живёт недолго — пока идёт шаг диалога.
 */
public interface GuidingQuestionMemory {

    Optional<String> last(Long chatId);

    void remember(Long chatId, String question);

    void forget(Long chatId);
}
