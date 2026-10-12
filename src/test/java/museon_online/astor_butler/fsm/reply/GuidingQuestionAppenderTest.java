package museon_online.astor_butler.fsm.reply;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.i18n.GuestTexts;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GuidingQuestionAppenderTest {

    private static final long CHAT_ID = 4242L;

    private final InMemoryGuidingQuestionMemory memory = new InMemoryGuidingQuestionMemory();
    private final GuidingQuestionAppender appender = new GuidingQuestionAppender(new GuidingQuestionPolicy(), memory, true);
    private final IncomingMessage incoming = incoming("завтра");

    @Test
    void appendsTheQuestionOnAnUnfinishedStep() {
        OutgoingMessage reply = reply("Записал: завтра.  ", BotState.TABLE_BOOKING_COLLECT_TIME, List.of("TABLE_BOOKING_ASK_TIME"));

        OutgoingMessage result = appender.append(incoming, reply);

        assertThat(result.text()).isEqualTo("Записал: завтра." + GuidingQuestionAppender.SEPARATOR + GuidingQuestionPolicy.ASK_TIME);
        assertThat(result.metadata()).containsEntry(GuidingQuestionAppender.METADATA_GUIDING_QUESTION, GuidingQuestionPolicy.ASK_TIME)
                .containsEntry("correlationId", "corr-1");
        assertThat(result.nextState()).isEqualTo(reply.nextState());
        assertThat(result.actions()).isEqualTo(reply.actions());
        assertThat(result.html()).isEqualTo(reply.html());
        assertThat(result.removeKeyboard()).isEqualTo(reply.removeKeyboard());
        assertThat(result.adminAlert()).isEqualTo(reply.adminAlert());
        assertThat(result.createdAt()).isEqualTo(reply.createdAt());
        assertThat(memory.last(CHAT_ID)).contains(GuidingQuestionPolicy.ASK_TIME);
    }

    @Test
    void noQuestionAfterTheScenarioIsFinished() {
        OutgoingMessage reply = reply("Бронь подтверждена. Ждем вас в AERIS.", BotState.READY_FOR_DIALOG, List.of("TABLE_BOOKING_CONFIRMED"));
        memory.remember(CHAT_ID, GuidingQuestionPolicy.ASK_TIME);

        OutgoingMessage result = appender.append(incoming, reply);

        assertThat(result).isSameAs(reply);
        assertThat(memory.last(CHAT_ID)).isEmpty();
    }

    @Test
    void noQuestionWhenTheReplyAlreadyEndsWithOne() {
        OutgoingMessage reply = reply("Этот день уже прошел. На какой день держим стол?", BotState.TABLE_BOOKING_COLLECT_DATE, List.of());

        OutgoingMessage result = appender.append(incoming, reply);

        assertThat(result).isSameAs(reply);
        assertThat(memory.last(CHAT_ID)).isEmpty();
    }

    @Test
    void theScenariosOwnIdenticalQuestionCountsAsAlreadyAsked() {
        OutgoingMessage own = reply("Понял. " + GuidingQuestionPolicy.ASK_DATE, BotState.TABLE_BOOKING_COLLECT_DATE, List.of());
        OutgoingMessage retry = reply("Не смог понять день. Напишите, например, «завтра».", BotState.TABLE_BOOKING_COLLECT_DATE, List.of());

        assertThat(appender.append(incoming, own)).isSameAs(own);
        assertThat(memory.last(CHAT_ID)).contains(GuidingQuestionPolicy.ASK_DATE);
        assertThat(appender.append(incoming, retry)).isSameAs(retry);
    }

    @Test
    void doesNotRepeatTheQuestionItJustAskedButAsksAgainAfterAnotherStep() {
        OutgoingMessage first = reply("Понял, бронь стола.", BotState.TABLE_BOOKING_COLLECT_DATE, List.of());
        OutgoingMessage retry = reply("Не смог понять день. Выберите его кнопкой.", BotState.TABLE_BOOKING_COLLECT_DATE, List.of());
        OutgoingMessage time = reply("Записал: завтра.", BotState.TABLE_BOOKING_COLLECT_TIME, List.of());
        OutgoingMessage backToDate = reply("Хорошо, меняем день.", BotState.TABLE_BOOKING_COLLECT_DATE, List.of());

        assertThat(appender.append(incoming, first).text()).endsWith(GuidingQuestionPolicy.ASK_DATE);
        assertThat(appender.append(incoming, retry)).isSameAs(retry);
        assertThat(appender.append(incoming, time).text()).endsWith(GuidingQuestionPolicy.ASK_TIME);
        assertThat(appender.append(incoming, backToDate).text()).endsWith(GuidingQuestionPolicy.ASK_DATE);
    }

    @Test
    void flagOffReturnsTheVerySameReplyAndNeverTouchesTheMemory() {
        GuidingQuestionMemory untouched = mock(GuidingQuestionMemory.class);
        GuidingQuestionAppender off = new GuidingQuestionAppender(new GuidingQuestionPolicy(), untouched, false);
        OutgoingMessage reply = reply("Понял, бронь стола.", BotState.TABLE_BOOKING_COLLECT_DATE, List.of());

        OutgoingMessage result = off.append(incoming, reply);

        assertThat(result).isSameAs(reply);
        assertThat(off.enabled()).isFalse();
        verifyNoInteractions(untouched);
    }

    @Test
    void theScenarioTagWinsOverTheStateOwner() {
        OutgoingMessage changeCancel = reply(
                "Понял, нужно изменить или отменить бронь. Напишите дату, время или номер заявки.",
                BotState.TABLE_BOOKING_CHANGE_REQUESTED,
                List.of("CHANGE_CANCEL", "ASK_ACTIVE_ORDER_REFERENCE")
        ).withMetadata(Map.of("scenario", "CHANGE_CANCEL"));
        OutgoingMessage untagged = reply("Понял.", BotState.TABLE_BOOKING_CHANGE_REQUESTED, List.of("ASK_ACTIVE_ORDER_REFERENCE"));

        assertThat(appender.append(incoming, changeCancel).text()).endsWith(GuidingQuestionPolicy.ASK_WHAT_TO_CHANGE);
        assertThat(appender.append(incoming, untagged)).isSameAs(untagged);
    }

    @Test
    void skipsFallbackPausedContactRequestAndForeignLanguageReplies() {
        OutgoingMessage fallback = new OutgoingMessage(incoming.channel(), incoming.externalUserId(), CHAT_ID, "Передам команде.",
                BotState.TABLE_BOOKING_COLLECT_DATE.name(), false, false, false, true, AdminAlert.none(), List.of(), Map.of(), java.time.Instant.now());
        OutgoingMessage paused = reply("Команда получила сообщение.", BotState.TABLE_BOOKING_COLLECT_DATE, List.of("BUTLER_PAUSED", "FORWARDED_TO_STAFF"));
        OutgoingMessage contact = new OutgoingMessage(incoming.channel(), incoming.externalUserId(), CHAT_ID, "Поделитесь контактом.",
                BotState.TABLE_BOOKING_COLLECT_DATE.name(), false, true, false, false, AdminAlert.none(), List.of(), Map.of(), java.time.Instant.now());
        OutgoingMessage english = reply("Noted: tomorrow.", BotState.TABLE_BOOKING_COLLECT_TIME, List.of())
                .withMetadata(Map.of(GuestTexts.METADATA_REPLY_LANGUAGE, "en"));
        OutgoingMessage russian = reply("Записал: завтра.", BotState.TABLE_BOOKING_COLLECT_TIME, List.of())
                .withMetadata(Map.of(GuestTexts.METADATA_REPLY_LANGUAGE, "ru"));
        OutgoingMessage empty = reply("", BotState.TABLE_BOOKING_COLLECT_TIME, List.of());
        OutgoingMessage unknownState = reply("Записал.", "NOT_A_STATE", List.of());

        assertThat(appender.append(incoming, fallback)).isSameAs(fallback);
        assertThat(appender.append(incoming, paused)).isSameAs(paused);
        assertThat(appender.append(incoming, contact)).isSameAs(contact);
        assertThat(appender.append(incoming, english)).isSameAs(english);
        assertThat(appender.append(incoming, empty)).isSameAs(empty);
        assertThat(appender.append(incoming, unknownState)).isSameAs(unknownState);
        assertThat(appender.append(incoming, null)).isNull();
        assertThat(appender.append(incoming, russian).text()).endsWith(GuidingQuestionPolicy.ASK_TIME);
    }

    @Test
    void aBrokenMemoryDoesNotBreakTheReply() {
        GuidingQuestionMemory broken = new GuidingQuestionMemory() {
            @Override
            public Optional<String> last(Long chatId) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public void remember(Long chatId, String question) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public void forget(Long chatId) {
                throw new IllegalStateException("redis down");
            }
        };
        GuidingQuestionAppender resilient = new GuidingQuestionAppender(new GuidingQuestionPolicy(), broken, true);
        OutgoingMessage reply = reply("Понял, бронь стола.", BotState.TABLE_BOOKING_COLLECT_DATE, List.of());

        assertThat(resilient.append(incoming, reply).text()).endsWith(GuidingQuestionPolicy.ASK_DATE);
        assertThat(resilient.append(incoming, reply("Готово.", BotState.READY_FOR_DIALOG, List.of()))).isNotNull();
    }

    @Test
    void keepsHtmlAndOtherFieldsWhenAppending() {
        OutgoingMessage reply = new OutgoingMessage(incoming.channel(), incoming.externalUserId(), CHAT_ID, "<b>Заявка отправлена</b> хостес.",
                BotState.TABLE_BOOKING_WAIT_HOSTESS_CONFIRMATION.name(), true, false, true, false, AdminAlert.none(),
                List.of("TABLE_BOOKING_REQUEST_SENT"), Map.of("correlationId", "corr-1", "replyKeyboardRows", List.of(List.of("Меню"))), java.time.Instant.now());

        OutgoingMessage result = appender.append(incoming, reply);

        assertThat(result.text()).isEqualTo("<b>Заявка отправлена</b> хостес." + GuidingQuestionAppender.SEPARATOR + GuidingQuestionPolicy.OFFER_MENU_WHILE_TEAM_CONFIRMS);
        assertThat(result.html()).isTrue();
        assertThat(result.removeKeyboard()).isTrue();
        assertThat(result.metadata()).containsEntry("replyKeyboardRows", List.of(List.of("Меню")));
    }

    private static OutgoingMessage reply(String text, BotState nextState, List<String> actions) {
        return reply(text, nextState.name(), actions);
    }

    private static OutgoingMessage reply(String text, String nextState, List<String> actions) {
        return OutgoingMessage.of(incoming("x"), text, nextState, false, false, false, false, AdminAlert.none(), actions);
    }

    private static IncomingMessage incoming(String text) {
        return IncomingMessage.telegram(CHAT_ID, 77L, 1, 1, text, null, "Гость", null, "guest", "ru", false, "corr-1");
    }

    /** Map-backed memory for tests: what Redis holds per chat. */
    static final class InMemoryGuidingQuestionMemory implements GuidingQuestionMemory {

        private final Map<Long, String> questions = new HashMap<>();

        @Override
        public Optional<String> last(Long chatId) {
            return Optional.ofNullable(questions.get(chatId));
        }

        @Override
        public void remember(Long chatId, String question) {
            questions.put(chatId, question);
        }

        @Override
        public void forget(Long chatId) {
            questions.remove(chatId);
        }
    }
}
