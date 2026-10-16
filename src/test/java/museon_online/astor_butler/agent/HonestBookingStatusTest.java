package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.talk.VenueFacts;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import museon_online.astor_butler.domain.summary.AgentLessonRepository;
import museon_online.astor_butler.domain.summary.GuestMemory;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

class HonestBookingStatusTest {

    private static final String LIVE = "Ваш стол у окна на 13:00 забронирован";
    private static final String HONEST = "Заявку на стол у окна, 15.10 в 13:00 передал хостес. Как только она подтвердит — сразу сообщу.";

    private static AgentOutcome booked(String text, String status) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("reservationId", 9L);
        facts.put("status", status);
        facts.put("table", "стол у окна");
        facts.put("date", "2026-10-15");
        facts.put("time", "13:00");
        CapabilityResult result = CapabilityResult.ok(facts);
        return new AgentOutcome(text, List.of(result), false, 1, List.of(new AgentOutcome.ToolUse("reservation_create", result)));
    }

    @Test
    void theLiveClaimWhileAwaitingTheHostessIsReplaced() {
        assertThat(HonestBookingStatus.guard(LIVE, booked(LIVE, "AWAITING_HOSTESS").uses())).isEqualTo(HONEST);
    }

    @Test
    void otherClaimsOfConfirmationAreReplacedToo() {
        for (String claim : List.of("Бронь подтверждена!", "Бронь оформлена, до встречи", "Ждём вас в 13:00",
                "Ожидаем вашего визита", "ожидаем вас", "ПОДТВЕРЖДАЮ стол")) {
            assertThat(HonestBookingStatus.guard(claim, booked(claim, "PENDING").uses())).as(claim).isEqualTo(HONEST);
        }
    }

    @Test
    void anHonestTextAndAConfirmedBookingStayAsTheyAre() {
        String honest = "Заявку на 13:00 у окна передал хостес, скоро вернусь с ответом.";
        assertThat(HonestBookingStatus.guard(honest, booked(honest, "AWAITING_HOSTESS").uses())).isEqualTo(honest);
        assertThat(HonestBookingStatus.guard(LIVE, booked(LIVE, "CONFIRMED").uses())).isEqualTo(LIVE);
        assertThat(HonestBookingStatus.guard(LIVE, List.of())).isEqualTo(LIVE);
    }

    @Test
    void missingFactsAreOmittedGracefully() {
        CapabilityResult result = CapabilityResult.ok(Map.of("status", "AWAITING_HOSTESS", "time", "19:30"));
        List<AgentOutcome.ToolUse> uses = List.of(new AgentOutcome.ToolUse("reservation_create", result));
        assertThat(HonestBookingStatus.guard(LIVE, uses))
                .isEqualTo("Заявку на 19:30 передал хостес. Как только она подтвердит — сразу сообщу.");
        CapabilityResult bare = CapabilityResult.ok(Map.of("status", "AWAITING_HOSTESS"));
        assertThat(HonestBookingStatus.guard(LIVE, List.of(new AgentOutcome.ToolUse("reservation_create", bare))))
                .isEqualTo("Заявку передал хостес. Как только она подтвердит — сразу сообщу.");
    }

    /** Prod run 16.10, turn «20:30»: nothing was created, yet the reply said the booking was made. */
    private static final String UNBACKED = "Заявка ждёт подтверждения хостес. Бронь на 5 человек в среду в 20:30 оформлена.";

    @Test
    void aBookingNoToolMadeIsAnUnbackedClaim() {
        CapabilityResult refused = new CapabilityResult(false, Map.of("reason", "needs_guest_yes"), CapabilityResult.NOT_ALLOWED);
        List<AgentOutcome.ToolUse> askedForTheYes = List.of(new AgentOutcome.ToolUse("reservation_create", refused));

        for (String claim : List.of(UNBACKED, "Ваш стол забронирован", "Заявку передал хостес.", "Заявка создана, ждём вас!")) {
            assertThat(HonestBookingStatus.unbacked(claim, List.of(), null)).as(claim).isTrue();
            assertThat(HonestBookingStatus.unbacked(claim, askedForTheYes, null)).as(claim).isTrue();
        }
    }

    @Test
    void aBookingMadeOrHeldOrAQuestionIsNoUnbackedClaim() {
        // Made this turn, or the guest already has a visit: the status talk is about a real booking.
        assertThat(HonestBookingStatus.unbacked(UNBACKED, booked(UNBACKED, "AWAITING_HOSTESS").uses(), null)).isFalse();
        CapabilityResult moved = CapabilityResult.ok(Map.of("reservationId", 9L, "status", "AWAITING_HOSTESS"));
        assertThat(HonestBookingStatus.unbacked(UNBACKED, List.of(new AgentOutcome.ToolUse("reservation_change", moved)), null))
                .isFalse();
        assertThat(HonestBookingStatus.unbacked(UNBACKED, List.of(), 7L)).isFalse();
        // Asking for the yes, or saying it is not done yet, claims nothing.
        for (String text : java.util.Arrays.asList(null, "На 20:30 есть стол у окна. Подтверждаете бронь?",
                "Бронь ещё не оформлена: подтвердите, пожалуйста, время.", "Стол пока не забронирован.")) {
            assertThat(HonestBookingStatus.unbacked(text, List.of(), null)).as(String.valueOf(text)).isFalse();
        }
    }

    @Test
    void anUnbackedClaimFallsBackOnBothPaths() {
        AgentLoop loop = mock(AgentLoop.class);
        when(loop.run(anyList(), anyList(), any(), anyBoolean(), anyLong())).thenReturn(
                new AgentOutcome(UNBACKED, List.of(), false, 0, List.of()));
        ButlerAgent agent = agent(loop);

        NegotiationReply reply = agent.negotiate(new NegotiationTurn("c-2", null, "20:30", Map.of(), "Мария", null,
                "Гостья хочет 20:30.", null, true));
        IncomingMessage incoming = IncomingMessage.telegram(501L, 501L, 1, 100, "20:30", null, "Мария", null, "maria", "ru", false, "c");

        assertThat(reply.fallback()).isTrue();
        assertThat(reply.butlerText()).isNull();
        assertThat(agent.reply(incoming, null)).isEmpty();
    }

    private static ButlerAgent agent(AgentLoop loop) {
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setAllowlist("501");
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        GuestVisitRepository visits = mock(GuestVisitRepository.class);
        when(visits.activeByChat(anyLong(), any())).thenReturn(Optional.empty());
        return new ButlerAgent(loop, mock(CapabilityRegistry.class), properties, mock(AgentLessonRepository.class),
                mock(GuestMemory.class), new VenueFactsCatalog(new VenueFacts(null, null, null, null, null)),
                new CapabilityProperties(), mock(LedgerWriter.class), turns, visits);
    }

    @Test
    void bothReplyPathsOfTheAgentAreGuarded() {
        AgentLoop loop = mock(AgentLoop.class);
        when(loop.run(anyList(), anyList(), any(), anyBoolean(), anyLong())).thenReturn(booked(LIVE, "AWAITING_HOSTESS"));
        AgentProperties properties = new AgentProperties();
        properties.setEnabled(true);
        properties.setAllowlist("501");
        VisitTurnRepository turns = mock(VisitTurnRepository.class);
        when(turns.visitOfSession(anyString())).thenReturn(Optional.empty());
        GuestVisitRepository visits = mock(GuestVisitRepository.class);
        when(visits.activeByChat(anyLong(), any())).thenReturn(Optional.empty());
        ButlerAgent agent = new ButlerAgent(loop, mock(CapabilityRegistry.class), properties, mock(AgentLessonRepository.class),
                mock(GuestMemory.class), new VenueFactsCatalog(new VenueFacts(null, null, null, null, null)),
                new CapabilityProperties(), mock(LedgerWriter.class), turns, visits);

        NegotiationReply reply = agent.negotiate(new NegotiationTurn("c-1", null, "бронируйте", Map.of(), "Мария", null,
                "Гостья хочет стол у окна.", null, true));
        assertThat(reply.butlerText()).isEqualTo(HONEST);

        IncomingMessage incoming = IncomingMessage.telegram(501L, 501L, 1, 100, "бронируйте", null, "Мария", null, "maria", "ru", false, "c");
        assertThat(agent.reply(incoming, null)).contains(HONEST);
    }
}
