package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.TurnChannel;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelTool;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ButlerAgentTest {

    private AgentLoop loop;
    private CapabilityRegistry registry;
    private AgentProperties properties;
    private AgentLessonRepository lessons;
    private GuestMemory memory;
    private LedgerWriter ledger;
    private VisitTurnRepository turns;
    private GuestVisitRepository visits;
    private ButlerAgent agent;
    private final List<List<ModelChatMessage>> histories = new ArrayList<>();
    private final List<CapabilityContext> contexts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        loop = mock(AgentLoop.class);
        registry = mock(CapabilityRegistry.class);
        when(registry.asModelTools()).thenReturn(List.of(new ModelTool("reservation_create", "Заявка", null)));
        properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setAllowlist("501, 502");
        lessons = mock(AgentLessonRepository.class);
        memory = mock(GuestMemory.class);
        ledger = mock(LedgerWriter.class);
        turns = mock(VisitTurnRepository.class);
        visits = mock(GuestVisitRepository.class);
        when(visits.activeByChat(anyLong(), any())).thenReturn(Optional.empty());
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        agent = new ButlerAgent(loop, registry, properties, lessons, memory,
                new VenueFactsCatalog(new VenueFacts("Парковка во дворе", null, null, null, null)), new CapabilityProperties(),
                ledger, turns, visits);
    }

    @SuppressWarnings("unchecked")
    private void loopAnswers(AgentOutcome outcome) {
        when(loop.run(anyList(), anyList(), any(), anyBoolean(), anyLong())).thenAnswer(invocation -> {
            histories.add(List.copyOf((List<ModelChatMessage>) invocation.getArgument(0)));
            contexts.add(invocation.getArgument(2));
            return outcome;
        });
    }

    private static NegotiationTurn turn(Long visitId, String guestText) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("time", "13:00");
        position.put("partySize", 2);
        position.put("budget", 1500);
        position.put("constraints", List.of("без лука"));
        return new NegotiationTurn("c-1", visitId, guestText, position, "Мария", "+79120000060",
                "Гостья хочет обед на двоих в 13:00.", null, true);
    }

    @Test
    void acceptedLessonsAndGuestMemoryAreInThePrompt() {
        when(lessons.accepted("BUTLER")).thenReturn(List.of("Предлагай веранду в тёплые дни"));
        when(memory.of("+79120000060", null)).thenReturn(List.of("Любит стол у окна"));
        loopAnswers(new AgentOutcome("Есть стол у окна в 13:00.", List.of(), false, 0));

        NegotiationReply reply = agent.negotiate(turn(null, "мой номер 8 912 000-00-60, стол на двоих"));

        assertThat(reply.fallback()).isFalse();
        assertThat(reply.butlerText()).isEqualTo("Есть стол у окна в 13:00.");
        assertThat(reply.offer()).isNull();
        List<ModelChatMessage> history = histories.get(0);
        String system = history.get(0).content();
        assertThat(history.get(0).role()).isEqualTo("system");
        assertThat(system).contains("Предлагай веранду в тёплые дни").contains("Любит стол у окна")
                .contains("Парковка во дворе").contains("бизнес-ланч").contains("90 минут").contains("lunch_order_place");
        String user = history.get(history.size() - 1).content();
        assertThat(user).contains("Позиция гостя: 13:00, 2 гостя, бюджет 1500, без лука")
                .contains("Гостья хочет обед на двоих в 13:00.")
                .contains("+7 912 ***-**-60")
                .doesNotContain("000-00-60");
        assertThat(String.join("\n", history.stream().map(ModelChatMessage::content).toList())).doesNotContain("79120000060");
        CapabilityContext ctx = contexts.get(0);
        assertThat(ctx.sessionId()).isEqualTo("c-1");
        assertThat(ctx.guestPhone()).isEqualTo("+79120000060");
        assertThat(ctx.idempotencyKey()).isEqualTo("c-1:1");
    }

    @Test
    void aReservationLinksTheConciergeSession() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reservationId", 9L);
        facts.put("status", "AWAITING_HOSTESS");
        facts.put("table", "У окна");
        facts.put("visitId", 42L);
        loopAnswers(new AgentOutcome("Заявка на 13:00 у окна передана хостес.", List.of(CapabilityResult.ok(facts)), false, 1,
                List.of(new AgentOutcome.ToolUse("reservation_create", CapabilityResult.ok(facts)))));

        NegotiationReply reply = agent.negotiate(turn(null, "да, бронируйте"));

        assertThat(reply.visitId()).isEqualTo(42L);
        assertThat(reply.offer()).containsEntry("kind", "RESERVATION").containsEntry("reservationId", 9L);
        assertThat(reply.facts()).singleElement().satisfies(fact -> {
            assertThat(fact).containsEntry("tool", "reservation_create").containsEntry("ok", true);
        });
        verify(ledger).linkSession("c-1", 42L);
        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, org.mockito.Mockito.times(2)).write(drafts.capture());
        VisitTurnDraft concierge = drafts.getAllValues().get(0);
        assertThat(concierge.side()).isEqualTo(TurnSide.CONCIERGE);
        assertThat(concierge.channel()).isEqualTo(TurnChannel.CONCIERGE_TG);
        assertThat(concierge.kind()).isEqualTo(TurnKind.MESSAGE);
        assertThat(concierge.text()).isEqualTo("Гостья хочет обед на двоих в 13:00.");
        assertThat(concierge.goal()).isEqualTo("GUEST");
        assertThat(concierge.sessionId()).isEqualTo("c-1");
        VisitTurnDraft butler = drafts.getAllValues().get(1);
        assertThat(butler.side()).isEqualTo(TurnSide.BUTLER);
        assertThat(butler.kind()).isEqualTo(TurnKind.OFFER);
        assertThat(butler.goal()).isEqualTo("VENUE");
        assertThat(butler.visitId()).isEqualTo(42L);
        assertThat(butler.text()).isEqualTo("Заявка на 13:00 у окна передана хостес.");

        // The next turn of the same session reuses the visit: never a second one for one session.
        agent.negotiate(turn(null, "спасибо"));
        assertThat(contexts.get(1).visitId()).isEqualTo(42L);
        assertThat(contexts.get(1).idempotencyKey()).isEqualTo("c-1:2");
        assertThat(histories.get(1).stream().map(ModelChatMessage::content).toList())
                .anySatisfy(content -> assertThat(content).contains("Заявка на 13:00 у окна передана хостес."));
    }

    @Test
    void aVisitLinkedEarlierIsReused() {
        when(turns.visitOfSession("c-1")).thenReturn(Optional.of(77L));
        loopAnswers(new AgentOutcome("Слушаю.", List.of(), false, 0));

        NegotiationReply reply = agent.negotiate(turn(null, "а что с моей бронью?"));

        assertThat(contexts.get(0).visitId()).isEqualTo(77L);
        assertThat(reply.visitId()).isEqualTo(77L);
    }

    @Test
    void fallbackIsWrittenAndNoTextReturned() {
        loopAnswers(AgentOutcome.fallback(List.of(), 0));

        NegotiationReply reply = agent.negotiate(turn(5L, "стол"));

        assertThat(reply.fallback()).isTrue();
        assertThat(reply.butlerText()).isNull();
        assertThat(reply.visitId()).isEqualTo(5L);
        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, org.mockito.Mockito.times(2)).write(drafts.capture());
        assertThat(drafts.getAllValues().get(1).kind()).isEqualTo(TurnKind.FALLBACK);
        assertThat(drafts.getAllValues().get(1).side()).isEqualTo(TurnSide.BUTLER);
    }

    @Test
    void switchedOffAgentIsAFallbackWithoutTheModel() {
        properties.setEnabled(false);

        NegotiationReply reply = agent.negotiate(turn(null, "стол"));

        assertThat(reply.fallback()).isTrue();
        verify(loop, never()).run(anyList(), anyList(), any());
    }

    @Test
    void directBotAnswersOnlyAllowlistedChats() {
        loopAnswers(new AgentOutcome("Добрый день! Чем помочь?", List.of(), false, 0));

        assertThat(agent.reply(guest(501L, "привет"), null)).contains("Добрый день! Чем помочь?");
        assertThat(agent.reply(guest(999L, "привет"), null)).isEmpty();
        assertThat(contexts).singleElement().satisfies(ctx -> {
            assertThat(ctx.caller()).isEqualTo(CapabilityContext.BUTLER_AGENT);
            assertThat(ctx.chatId()).isEqualTo(501L);
        });
    }

    @Test
    void withAStarAllowlistTheDirectBotAnswersAnUnlistedPrivateChat() {
        properties.setAllowlist("*");
        loopAnswers(new AgentOutcome("Добрый день! Чем помочь?", List.of(), false, 0));

        assertThat(agent.reply(guest(777L, "привет"), null)).contains("Добрый день! Чем помочь?");
        assertThat(agent.reply(guest(-1001234567890L, "привет"), null)).isEmpty();
        assertThat(contexts).singleElement().satisfies(ctx -> assertThat(ctx.chatId()).isEqualTo(777L));
    }

    @Test
    void directBotFallbackIsEmptyAndWritten() {
        loopAnswers(AgentOutcome.fallback(List.of(), 0));

        assertThat(agent.reply(guest(501L, "привет"), 9L)).isEmpty();

        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger).write(drafts.capture());
        assertThat(drafts.getValue().kind()).isEqualTo(TurnKind.FALLBACK);
        assertThat(drafts.getValue().channel()).isEqualTo(TurnChannel.BUTLER_TG);
        assertThat(drafts.getValue().visitId()).isEqualTo(9L);
    }

    @Test
    void directBotFallbackWithoutAVisitWritesNoOrphanRow() {
        loopAnswers(AgentOutcome.fallback(List.of(), 0));

        assertThat(agent.reply(guest(501L, "привет"), null)).isEmpty();

        verify(ledger, never()).write(any());
    }

    @SuppressWarnings("unchecked")
    private void loopAnswersInTurn(AgentOutcome... outcomes) {
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        when(loop.run(anyList(), anyList(), any(), anyBoolean(), anyLong())).thenAnswer(invocation -> {
            histories.add(List.copyOf((List<ModelChatMessage>) invocation.getArgument(0)));
            contexts.add(invocation.getArgument(2));
            return outcomes[Math.min(next.getAndIncrement(), outcomes.length - 1)];
        });
    }

    @Test
    void fallbackWithAToolCallThenTheNextTurnGetsANewKey() {
        AgentOutcome.ToolUse booked = new AgentOutcome.ToolUse("reservation_create",
                CapabilityResult.ok(Map.of("reservationId", 9L, "status", "AWAITING_HOSTESS")));
        loopAnswersInTurn(AgentOutcome.fallback(List.of(booked), 1), new AgentOutcome("Чем помочь?", List.of(), false, 0));

        agent.reply(guest(501L, "стол на двоих"), null);
        agent.reply(guest(501L, "а меню?"), null);
        agent.negotiate(turn(null, "стол"));
        agent.negotiate(turn(null, "меню"));

        assertThat(contexts).extracting(CapabilityContext::idempotencyKey).containsExactly("tg:501:1", "tg:501:2", "c-1:1", "c-1:2");
    }

    @Test
    void aNegotiationFallbackWithoutToolCallsKeepsItsKeyForTheRetry() {
        loopAnswersInTurn(AgentOutcome.fallback(List.of(), 0), new AgentOutcome("Слушаю.", List.of(), false, 0));

        agent.negotiate(turn(null, "стол"));
        agent.negotiate(turn(null, "стол"));

        assertThat(contexts).extracting(CapabilityContext::idempotencyKey).containsExactly("c-1:1", "c-1:1");
    }

    @Test
    void longHistoryCollapsesIntoOneEarlierLine() {
        properties.setHistoryChars(400);
        loopAnswers(new AgentOutcome("Ответ ресторана ".repeat(5), List.of(), false, 0));

        for (int i = 0; i < 6; i++) {
            agent.reply(guest(501L, "вопрос номер " + i + " " + "слово ".repeat(10)), null);
        }

        List<ModelChatMessage> last = histories.get(histories.size() - 1);
        int chars = last.stream().skip(1).mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum();
        assertThat(chars).isLessThanOrEqualTo(400 + 200);
        assertThat(last.stream().map(ModelChatMessage::content).toList())
                .anySatisfy(content -> assertThat(content).startsWith("Ранее:"));
    }

    @Test
    void aLongSessionIdStillGivesAKeyTheStoreAccepts() {
        assertThat(ButlerAgent.turnKey("c-1", 3)).isEqualTo("c-1:3");
        String key = ButlerAgent.turnKey("s".repeat(200), 3);
        assertThat(key).endsWith(":3");
        assertThat((key + ":5").length()).isLessThanOrEqualTo(80);
        assertThat(ButlerAgent.turnKey("s".repeat(200), 3)).isEqualTo(key);
        assertThat(ButlerAgent.turnKey("c-1", "t-7")).isEqualTo("c-1:t-7");
        String longTurn = ButlerAgent.turnKey("c-1", "t".repeat(120));
        assertThat((longTurn + ":5").length()).isLessThanOrEqualTo(80);
        assertThat(ButlerAgent.turnKey("c-1", "t".repeat(120))).isEqualTo(longTurn).isNotEqualTo(ButlerAgent.turnKey("c-1", "u".repeat(120)));
    }

    @Test
    void withoutConsentTheConciergeRowIsHidden() {
        loopAnswers(new AgentOutcome("Есть стол у окна.", List.of(), false, 0));
        NegotiationTurn given = turn(null, "стол");
        agent.negotiate(new NegotiationTurn(given.sessionId(), null, given.guestText(), given.conciergePosition(), given.guestName(),
                given.guestPhone(), given.conciergeMessage(), null, false));
        agent.negotiate(new NegotiationTurn(given.sessionId(), null, given.guestText(), given.conciergePosition(), given.guestName(),
                given.guestPhone(), given.conciergeMessage(), null, null));

        ArgumentCaptor<VisitTurnDraft> drafts = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, org.mockito.Mockito.times(4)).write(drafts.capture());
        for (int i : new int[]{0, 2}) {
            VisitTurnDraft concierge = drafts.getAllValues().get(i);
            assertThat(concierge.side()).isEqualTo(TurnSide.CONCIERGE);
            assertThat(concierge.text()).isNull();
            assertThat(concierge.hidden()).isTrue();
        }
        assertThat(drafts.getAllValues().get(1).text()).isEqualTo("Есть стол у окна.");
        assertThat(drafts.getAllValues().get(3).text()).isEqualTo("Есть стол у окна.");
    }

    @Test
    void theTurnIdMakesTheKey() {
        loopAnswers(new AgentOutcome("Слушаю.", List.of(), false, 0));
        NegotiationTurn given = turn(null, "стол");
        NegotiationTurn withId = new NegotiationTurn(given.sessionId(), null, given.guestText(), given.conciergePosition(),
                given.guestName(), given.guestPhone(), given.conciergeMessage(), "t-7", true);

        agent.negotiate(withId);
        agent.negotiate(withId);

        assertThat(contexts).extracting(CapabilityContext::idempotencyKey).containsExactly("c-1:t-7", "c-1:t-7");
    }

    private static IncomingMessage guest(long chatId, String text) {
        return IncomingMessage.telegram(chatId, chatId, 1, 100, text, null, "Мария", null, "maria", "ru", false, "c");
    }

    @Test
    void thePromptGivesTodayAndTheDateFormat() {
        // 2026-10-14T20:00Z is already Thursday 15.10 in Yekaterinburg (UTC+5).
        agent.setClock(java.time.Clock.fixed(java.time.Instant.parse("2026-10-14T20:00:00Z"), java.time.ZoneOffset.UTC));
        loopAnswers(new AgentOutcome("Есть стол.", List.of(), false, 0));

        agent.negotiate(turn(null, "стол на завтра"));

        String system = histories.get(0).get(0).content();
        assertThat(system).contains("Сегодня: 2026-10-15, четверг").contains("YYYY-MM-DD").contains("завтра");
    }

    /** Live test 16.10: «Напишите коды блюд… SALAD_COLESLAW» reached the guest. */
    @Test
    void theGuestNeverReadsAnInternalDishCode() {
        agent.setLunchCatalog(new museon_online.astor_butler.domain.lunch.BusinessLunchCatalog(List.of(LunchDishNamesTest.AERIS)));
        loopAnswers(new AgentOutcome("Напишите коды блюд, например SALAD_COLESLAW.", List.of(), false, 0));

        NegotiationReply reply = agent.negotiate(turn(9L, "хочу бизнес-ланч"));
        Optional<String> bot = agent.reply(guest(501L, "хочу бизнес-ланч"), 9L);

        assertThat(reply.butlerText()).isEqualTo("Напишите коды блюд, например Салат коул слоу.");
        assertThat(bot).contains("Напишите коды блюд, например Салат коул слоу.");
    }

    /** «Хочу чизкейк с собой», then «Да»: the yes turn still carries the guest's request to pack. */
    @Test
    void theTakeawayOfTheTurnBeforeReachesTheTools() {
        loopAnswers(new AgentOutcome("Завернуть то, что в заказе, или добавить ещё одну порцию?", List.of(), false, 0));

        agent.reply(guest(501L, "Хочу чизкейк с собой"), 9L);
        agent.reply(guest(501L, "Да"), 9L);
        agent.reply(guest(501L, "Добавьте ещё одну"), 9L);

        assertThat(contexts.get(0).agreement().takeaway()).isTrue();
        assertThat(contexts.get(1).agreement().takeaway()).isTrue();
        assertThat(contexts.get(1).agreement().yes()).isTrue();
        assertThat(contexts.get(2).agreement().takeaway()).isFalse();
    }
}
