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
