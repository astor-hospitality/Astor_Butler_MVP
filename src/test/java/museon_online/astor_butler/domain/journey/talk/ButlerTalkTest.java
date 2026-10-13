package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.journey.VisitEventKind;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.integration.saby.SabyNutrition;
import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ButlerTalkTest {

    private static final long CHAT = 421441838L;

    private final ButlerTalkProperties properties = new ButlerTalkProperties();
    private final ButlerTalk.Nutrition nutrition = mock(ButlerTalk.Nutrition.class);
    private final TableRequestRouter router = mock(TableRequestRouter.class);
    private final ButlerTalk.TeamQuestions team = mock(ButlerTalk.TeamQuestions.class);
    private final JourneyEvents events = mock(JourneyEvents.class);
    private ButlerTalk talk;

    private static BusinessLunchOffer menu() {
        return new BusinessLunchOffer("AERIS", "AERIS", true, List.of(java.time.DayOfWeek.MONDAY), java.time.LocalTime.NOON, java.time.LocalTime.of(16, 0), 90, "", List.of(
                new BusinessLunchOffer.Course("MAIN", "Горячее", List.of(
                        new BusinessLunchOffer.Dish("PASTA_BOLOGNESE", "Паста болоньезе", 420, null, null, null, List.of(), null,
                                "Паста болоньезе Ланч")))), List.of());
    }

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        talk = new ButlerTalk(properties, new VenueFactsCatalog(new VenueFacts("Бесплатная парковка во дворе, въезд с ул. Мамина-Сибиряка.",
                null, null, null, null)), nutrition, new BusinessLunchCatalog(List.of(menu())), router, team, events, "AERIS");
    }

    private static IncomingMessage says(String text) {
        return IncomingMessage.telegram(CHAT, CHAT, 1, 100, text, null, "Мария", null, "maria", "ru", false, "c");
    }

    private static Optional<GuestVisit> seated() {
        return Optional.of(new GuestVisit(1L, 42L, CHAT, "+79120000000", "AERIS", JourneyStage.SEATED, null, null, 3,
                Instant.parse("2026-10-14T08:00:00Z"), Instant.parse("2026-10-13T14:00:00Z"), null));
    }

    @Test
    void caloriesQuestionAnsweredFromSaby() {
        when(nutrition.of("Паста болоньезе Ланч")).thenReturn(Optional.of(new SabyNutrition(280, 596, 33, 15, 82)));

        assertThat(talk.answer(says("а сколько калорий в пасте?"), BotState.READY_FOR_DIALOG, Optional.empty()))
                .contains("Паста болоньезе: 280 г, около 596 ккал (Б 33 · Ж 15 · У 82).");
        verify(events).onChat(eq(CHAT), eq(VisitEventKind.DISH_QUESTION), eq(JourneyEvents.GUEST), anyString());
    }

    @Test
    void sabyDownLeavesTheQuestionToTheUsualAnswer() {
        when(nutrition.of(anyString())).thenReturn(Optional.empty());

        assertThat(talk.answer(says("сколько калорий в пасте"), BotState.READY_FOR_DIALOG, Optional.empty())).isEmpty();
    }

    @Test
    void knownVenueFactAnswered() {
        assertThat(talk.answer(says("а где у вас парковка?"), BotState.READY_FOR_DIALOG, Optional.empty()))
                .contains("Бесплатная парковка во дворе, въезд с ул. Мамина-Сибиряка.");
        verify(events).onChat(eq(CHAT), eq(VisitEventKind.VENUE_QUESTION), eq(JourneyEvents.GUEST), anyString());
    }

    @Test
    void unknownVenueFactAsksTheTeam() {
        assertThat(talk.answer(says("можно с детьми прийти?"), BotState.READY_FOR_DIALOG, Optional.empty()))
                .contains(ButlerTalk.ASK_TEAM);
        verify(team).ask(CHAT, "можно с детьми прийти?");
    }

    @Test
    void tableRequestGoesToTheRouterWhenSeated() {
        when(router.route(42L, TableRequest.BREAD, "можно ещё хлеба")).thenReturn("Передал. Кирилл скоро подойдёт.");

        assertThat(talk.answer(says("можно ещё хлеба"), BotState.BUSINESS_LUNCH_CONFIRMATION, seated())).contains("Передал. Кирилл скоро подойдёт.");
        verify(events).onChat(eq(CHAT), eq(VisitEventKind.TABLE_REQUEST), eq(JourneyEvents.GUEST), anyString());
    }

    @Test
    void tableRequestIsNotTakenBeforeTheGuestIsSeated() {
        assertThat(talk.answer(says("можно ещё хлеба"), BotState.READY_FOR_DIALOG, Optional.empty())).isEmpty();
        verify(router, never()).route(any(Long.class), any(), anyString());
    }

    @Test
    void questionsWaitWhileAScenarioAsksSomething() {
        assertThat(talk.answer(says("а где у вас парковка?"), BotState.BUSINESS_LUNCH_COLLECT_WISHES, Optional.empty())).isEmpty();
    }

    @Test
    void flagOffAnswersNothing() {
        properties.setEnabled(false);

        assertThat(talk.answer(says("а где у вас парковка?"), BotState.READY_FOR_DIALOG, Optional.empty())).isEmpty();
    }
}
