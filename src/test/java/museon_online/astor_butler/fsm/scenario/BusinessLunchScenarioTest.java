package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchFixtures;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The business lunch as a guest goes through it. It is Monday 2026-10-05, 14:00 in Yekaterinburg; lunch is on weekdays from 12:00 to 16:00.
 * Everything below the scenario is real except the table reservations and the venue's own system.
 */
@ExtendWith(MockitoExtension.class)
class BusinessLunchScenarioTest {

    private static final long CHAT = 1773317437L;
    private static final Instant TUESDAY_13_00 = Instant.parse("2026-10-06T08:00:00Z");

    @Mock
    private TableReservationService tableReservationService;

    private final Map<Long, BotState> states = new HashMap<>();
    private final Map<Long, BusinessLunchDraftStorage.Draft> drafts = new HashMap<>();
    private final List<ExternalLunchOrderProvider> providers = new ArrayList<>();
    private BusinessLunchScenario scenario;

    @BeforeEach
    void setUp() {
        BookingTimeProvider timeProvider = new BookingTimeProvider(Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), BookingTimeProvider.VENUE_ZONE));
        scenario = new BusinessLunchScenario(
                new InMemoryStates(),
                new InMemoryDrafts(),
                new BusinessLunchCatalog(List.of(BusinessLunchFixtures.fullMenu(), BusinessLunchFixtures.dishOfTheDay(), BusinessLunchFixtures.aLaCarte())),
                new BusinessLunchService(tableReservationService, providers, timeProvider),
                new GuestInputUnderstandingService(),
                new TableBookingDraftMerger(mock(TableBookingDraftStorage.class), timeProvider),
                timeProvider
        );
        ReflectionTestUtils.setField(scenario, "adminChatId", "100500");
        states.put(CHAT, BotState.READY_FOR_DIALOG);
        lenient().when(tableReservationService.createReservation(any()))
                .thenReturn(BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2));
    }

    @Test
    void aGuestSentByTheConciergeOrdersALunchStepByStep() {
        OutgoingMessage set = say("/start lunch_aeris");
        assertThat(set.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_SET.name());
        assertThat(set.text()).contains("Бизнес-ланч в AERIS: по будням с 12:00 до 16:00.", "В ланч входит: чай или морс.", "Какой вариант выбираете?");
        assertThat(buttons(set)).containsExactly("Салат + суп · 590 ₽", "Салат или суп + горячее · 650 ₽", "Салат + суп + горячее · 690 ₽", "↩️ Отменить");

        OutgoingMessage starter = say("Салат или суп + горячее · 650 ₽");
        assertThat(starter.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_DISH.name());
        assertThat(starter.text()).isEqualTo("Салат или суп: что выбираете?");
        assertThat(buttons(starter)).containsExactly("Греческий салат", "Салат со свеклой", "Куриный бульон", "Крем-суп из тыквы", "↩️ Отменить");

        OutgoingMessage main = say("Куриный бульон");
        assertThat(main.text()).isEqualTo("Горячее: что выбираете?");
        assertThat(buttons(main)).containsExactly("Паста с томатами", "Курица с рисом", "↩️ Отменить");

        OutgoingMessage guests = say("1");
        assertThat(guests.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_PARTY_SIZE.name());

        OutgoingMessage day = say("на двоих");
        assertThat(day.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_DATE.name());
        assertThat(buttons(day)).containsExactly("Сегодня 05.10", "Завтра 06.10", "Ср 07.10", "Чт 08.10", "Пт 09.10", "↩️ Отменить");

        OutgoingMessage time = say("Завтра 06.10");
        assertThat(time.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());
        assertThat(buttons(time)).containsExactly("12:00", "12:30", "13:00", "13:30", "14:00", "14:30", "15:00", "15:30", "↩️ Отменить");

        OutgoingMessage summary = say("13:00");
        assertThat(summary.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(summary.text()).contains(
                "Бизнес-ланч в AERIS",
                "Вторник, 06.10 в 13:00",
                "Гостей: 2",
                "Комбо: «Салат или суп + горячее», 650 ₽ за ланч",
                "Блюда: Куриный бульон, Паста с томатами",
                "Отправляю заявку команде?");
        assertThat(buttons(summary)).containsExactly("✅ Отправить заявку", "✏️ Изменить", "↩️ Отменить");
        verify(tableReservationService, never()).createReservation(any());

        OutgoingMessage placed = say("✅ Отправить заявку");

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().requestedStartAt()).isEqualTo(TUESDAY_13_00);
        assertThat(command.getValue().partySize()).isEqualTo(2);
        assertThat(command.getValue().seatingPreference()).isEqualTo("Бизнес-ланч");
        assertThat(command.getValue().guestComment()).isEqualTo(
                "Бизнес-ланч из Concierge: 2 × «Салат или суп + горячее», 650 ₽. Куриный бульон, Паста с томатами. В Saby внести вручную.");
        assertThat(placed.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(placed.text()).contains("Заявку #77 на бизнес-ланч передал команде AERIS", "Как только хостес ответит");
        assertThat(placed.text().toLowerCase()).doesNotContain("подтвержден");
        assertThat(placed.actions()).containsExactly("BUSINESS_LUNCH", "LUNCH_ORDER_PLACED", "EXTERNAL_ORDER_MANUAL", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        assertThat(placed.metadata()).containsEntry("tableReservationId", 77L).containsEntry("lunchSource", "CONCIERGE").containsEntry("externalStatus", "MANUAL_ENTRY");
        assertThat(placed.adminAlert().required()).isFalse();
        assertThat(drafts).isEmpty();
    }

    @Test
    void aMenuWithoutSetsIsOrderedCourseByCourse() {
        OutgoingMessage salads = say("/start lunch_carte");
        assertThat(salads.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_DISH.name());
        assertThat(salads.text()).contains(
                "Бизнес-ланч в Carte: по будням с 12:00 до 16:00.",
                "Салаты\n• Нисуаз, 170 г · 290 ₽\n• Цезарь с цыплёнком, 130 г · 290 ₽",
                "Напитки\n• Клюквенный морс, 200 мл · 140 ₽\n• Капучино · 220 ₽",
                "Салаты: что добавить? Каждое нажатие добавляет одну порцию.");
        assertThat(salads.text()).doesNotContain("подтвердит команда");
        assertThat(buttons(salads)).containsExactly("Нисуаз · 290 ₽", "Цезарь с цыплёнком · 290 ₽", "➡️ Дальше", "↩️ Отменить");

        OutgoingMessage added = say("Нисуаз · 290 ₽");
        assertThat(added.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_DISH.name());
        assertThat(added.text()).isEqualTo("Добавил: Нисуаз.\n\nСалаты: что добавить? Каждое нажатие добавляет одну порцию.\nВ заказе: Нисуаз × 1. Итого 290 ₽.");

        OutgoingMessage soup = say("➡️ Дальше");
        assertThat(soup.text()).startsWith("Суп: что добавить?");
        assertThat(buttons(soup)).containsExactly("Борщ со сметаной · 270 ₽", "➡️ Дальше", "↩️ Отменить");
        say("Борщ со сметаной · 270 ₽");
        OutgoingMessage twice = say("борщ со сметаной");
        assertThat(twice.text()).contains("В заказе: Нисуаз × 1, Борщ со сметаной × 2. Итого 830 ₽.");

        assertThat(say("дальше").text()).startsWith("Напитки: что добавить?");
        // "Нет" to the drinks skips the drinks. It does not throw the whole order away.
        OutgoingMessage guests = say("нет");
        assertThat(guests.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_PARTY_SIZE.name());

        say("2");
        say("завтра");
        OutgoingMessage summary = say("13:00");
        assertThat(summary.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(summary.text()).contains(
                "Вторник, 06.10 в 13:00",
                "Гостей: 2",
                "Заказ:\n• Нисуаз × 1 · 290 ₽\n• Борщ со сметаной × 2 · 540 ₽\nИтого: 830 ₽");
        assertThat(summary.text()).doesNotContain("Комбо");
        verify(tableReservationService, never()).createReservation(any());

        OutgoingMessage placed = say("да");

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().venueCode()).isEqualTo("CARTE");
        assertThat(command.getValue().guestComment()).isEqualTo("Бизнес-ланч из Concierge: Нисуаз × 1, Борщ со сметаной × 2. Итого 830 ₽. В Saby внести вручную.");
        assertThat(placed.text()).contains("Заявку #77 на бизнес-ланч передал команде Carte", "Итого: 830 ₽");
        assertThat(placed.metadata()).containsEntry("lunchSet", "A_LA_CARTE");
    }

    @Test
    void anOrderWithNothingInItIsNotCarriedOn() {
        say("/start lunch_carte");
        say("дальше");
        say("пропустить");

        OutgoingMessage back = say("без напитков");

        assertThat(back.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_DISH.name());
        assertThat(back.text()).contains("В заказе пока ничего нет.", "Салаты: что добавить?");
        assertThat(say("отмена").nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
    }

    @Test
    void aDishFromAnotherCourseOrAStrangeWordIsNotAdded() {
        say("/start lunch_carte_p2");

        OutgoingMessage unknown = say("Борщ со сметаной");

        assertThat(unknown.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_DISH.name());
        assertThat(unknown.text()).contains("Не нашел такое блюдо в этом разделе.", "Салаты: что добавить?");
        assertThat(drafts.get(CHAT).dishCodes()).isEmpty();
    }

    @Test
    void theVenuesSystemGetsPortionsAndPricesOfAnOrderWithoutSets() {
        VenueSystem saby = new VenueSystem(new ExternalLunchOrderProvider.Result(true, "SABY", "ACCEPTED", "SABY-777", ""));
        providers.add(saby);
        say("/start lunch_carte_p2_d20261006_t1300");
        say("2");
        say("2");
        say("дальше");
        say("дальше");
        say("Капучино");
        say("дальше");

        say("да");

        assertThat(saby.order.setCode()).isNull();
        assertThat(saby.order.dishes()).containsExactly(
                new BusinessLunchOrder.Item("SALAD", "CAESAR", "Цезарь с цыплёнком", 2, 290),
                new BusinessLunchOrder.Item("DRINKS", "CAPPUCCINO", "Капучино", 1, 220));
        assertThat(saby.order.totalRub()).isEqualTo(800);
        verify(tableReservationService).attachExternalId(77L, "SABY-777");
    }

    @Test
    void whatTheConciergeAlreadyKnowsIsNotAskedAgain() {
        // One dish of the day per course, so there is nothing to choose inside the set either.
        OutgoingMessage summary = say("/start lunch_simple_s3_p2_d20261006_t1300_r7f3a");

        assertThat(summary.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(summary.text()).containsOnlyOnce("Состав и стоимость на день подтвердит команда.");
        assertThat(summary.text()).contains(
                "Бизнес-ланч в Simple: по будням с 12:00 до 16:00.",
                "Вторник, 06.10 в 13:00",
                "Гостей: 2",
                "Комбо: «Салат + суп + горячее»",
                "Блюда: Салат дня, Суп дня, Горячее дня");
        assertThat(summary.text()).doesNotContain("₽");
        verify(tableReservationService, never()).createReservation(any());

        say("да");

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().venueCode()).isEqualTo("SIMPLE");
        assertThat(command.getValue().guestComment()).isEqualTo("Бизнес-ланч из Concierge: 2 × «Салат + суп + горячее». Салат дня, Суп дня, Горячее дня. В Saby внести вручную.");
    }

    @Test
    void whatTheConciergePassedWrongIsAskedInsteadOfBeingBelieved() {
        // Set 9 does not exist, 40 guests is too many, the 10th is a Saturday and 19:00 is not lunch time.
        OutgoingMessage first = say("/start lunch_simple_s9_p40_d20261010_t1900");

        assertThat(first.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_SET.name());
        assertThat(say("3").nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_PARTY_SIZE.name());
        assertThat(say("2").nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_DATE.name());
        assertThat(say("завтра").nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());
    }

    @Test
    void aDayOrATimeOutsideTheLunchIsExplainedAndAskedAgain() {
        say("бизнес-ланч");
        say("1");
        say("Греческий салат");
        say("Куриный бульон");
        say("2");

        OutgoingMessage saturday = say("в субботу");
        assertThat(saturday.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_DATE.name());
        assertThat(saturday.text()).contains("Бизнес-ланч в AERIS проходит по будням.", "В какой день вас ждать?");

        OutgoingMessage nonsense = say("32.13");
        assertThat(nonsense.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_DATE.name());
        assertThat(nonsense.text()).contains("Не смог понять день.");

        assertThat(say("завтра").nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());

        OutgoingMessage evening = say("19:00");
        assertThat(evening.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());
        assertThat(evening.text()).contains("Бизнес-ланч подают с 12:00 до 16:00.");

        OutgoingMessage noTime = say("25:00");
        assertThat(noTime.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());
        assertThat(noTime.text()).contains("Не хочу гадать со временем.");

        OutgoingMessage summary = say("в 2 дня");
        assertThat(summary.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(summary.text()).contains("Вторник, 06.10 в 14:00", "Блюда: Греческий салат, Куриный бульон");
    }

    @Test
    void todayOffersOnlyTheTimeThatIsStillAhead() {
        say("бизнес-ланч на двоих");
        say("1");
        say("1");
        say("1");

        OutgoingMessage time = say("сегодня");

        assertThat(time.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());
        assertThat(buttons(time)).containsExactly("14:30", "15:00", "15:30", "↩️ Отменить");
        OutgoingMessage gone = say("13:00");
        assertThat(gone.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());
        assertThat(gone.text()).contains("Это время уже прошло.");
    }

    @Test
    void oneMessageWithTheDetailsSkipsTheQuestionsItAnswers() {
        OutgoingMessage set = say("Бизнес-ланч завтра в 13:00 на двоих");
        assertThat(set.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_SET.name());

        say("Салат + суп");
        say("Греческий салат");
        OutgoingMessage summary = say("Крем-суп из тыквы");

        assertThat(summary.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(summary.text()).contains("Вторник, 06.10 в 13:00", "Гостей: 2", "Комбо: «Салат + суп», 590 ₽ за ланч");
    }

    @Test
    void theGuestCanLeaveAtAnyStepAndNothingIsPlaced() {
        say("бизнес-ланч");
        say("2");

        OutgoingMessage left = say("↩️ Отменить");

        assertThat(left.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(left.text()).contains("бизнес-ланч не оформляю");
        assertThat(left.actions()).contains("LUNCH_CANCELLED_BY_GUEST");
        assertThat(drafts).isEmpty();
        assertThat(states.get(CHAT)).isEqualTo(BotState.READY_FOR_DIALOG);
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void atTheSummaryTheGuestCanCorrectAddAWishOrStartOver() {
        say("/start lunch_simple_s1_p2_d20261006_t1300");

        OutgoingMessage later = say("лучше в 14:30");
        assertThat(later.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(later.text()).contains("Поправил.", "Вторник, 06.10 в 14:30");

        OutgoingMessage tooLate = say("давайте в 18:00");
        assertThat(tooLate.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(tooLate.text()).contains("Бизнес-ланч подают с 12:00 до 16:00.", "Вторник, 06.10 в 14:30");

        OutgoingMessage wish = say("Один гость не ест лук");
        assertThat(wish.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CONFIRMATION.name());
        assertThat(wish.text()).contains("Пожелание записал.", "Пожелание: Один гость не ест лук");
        verify(tableReservationService, never()).createReservation(any());

        OutgoingMessage again = say("✏️ Изменить");
        assertThat(again.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_SET.name());
        assertThat(again.text()).contains("Хорошо, соберем заново.");
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void theWishReachesTheHostess() {
        say("/start lunch_simple_s1_p2_d20261006_t1300");
        say("Один гость не ест лук");

        say("да, отправить");

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().guestComment()).contains("Пожелание: Один гость не ест лук.");
    }

    @Test
    void whenThereIsNoFreeTableTheGuestPicksAnotherTime() {
        when(tableReservationService.createReservation(any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "No available tables for requested time and party size"));
        say("/start lunch_simple_s1_p2_d20261006_t1300");

        OutgoingMessage other = say("да");

        assertThat(other.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_COLLECT_TIME.name());
        assertThat(other.text()).contains("На это время свободного стола для вашей компании нет.", "Во сколько вас ждать?");
        assertThat(drafts.get(CHAT).time()).isNull();
        assertThat(drafts.get(CHAT).date()).isNotNull();
    }

    @Test
    void theSameLunchTwiceIsNotPlacedTwice() {
        when(tableReservationService.listActiveReservationsByChatId(CHAT))
                .thenReturn(List.of(BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2)));
        say("/start lunch_simple_s1_p2_d20261006_t1300");

        OutgoingMessage repeated = say("да");

        assertThat(repeated.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(repeated.text()).contains("У вас уже есть заявка #77 на это время, вторую не создаю.");
        assertThat(repeated.actions()).contains("LUNCH_ORDER_ALREADY_PLACED");
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aLunchFromTheConciergeWaitsForConsentAndThenGoesOn() {
        states.put(CHAT, BotState.UNKNOWN);
        IncomingMessage link = telegram("/start lunch_simple_p2", null);
        scenario.rememberHandoff(link, link.text());
        OutgoingMessage askConsent = firstTouch(link, BotState.CONSENT_REQUIRED, "REQUEST_CONTACT");

        // Consent is not given yet: the reply about consent goes out as it is, and the lunch waits.
        assertThat(scenario.continueAfterFirstTouch(link, askConsent)).isSameAs(askConsent);
        assertThat(drafts.get(CHAT).partySize()).isEqualTo(2);

        IncomingMessage contact = telegram("", "+79990000000");
        scenario.rememberHandoff(contact, contact.text());
        OutgoingMessage resumed = scenario.continueAfterFirstTouch(contact, firstTouch(contact, BotState.READY_FOR_DIALOG, "CONTACT_CAPTURED"));

        assertThat(resumed.nextState()).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_SET.name());
        assertThat(resumed.text()).startsWith("Спасибо, контакт получил.")
                .contains("Бизнес-ланч в Simple: по будням с 12:00 до 16:00. Состав и стоимость на день подтвердит команда.", "Какой вариант выбираете?");
        assertThat(states.get(CHAT)).isEqualTo(BotState.BUSINESS_LUNCH_CHOOSE_SET);
    }

    @Test
    void aBareStartForgetsAnUnfinishedLunch() {
        say("бизнес-ланч");
        assertThat(drafts).containsKey(CHAT);
        IncomingMessage start = telegram("/start", null);

        scenario.rememberHandoff(start, start.text());
        OutgoingMessage menu = firstTouch(start, BotState.READY_FOR_DIALOG, "OPEN_MENU");

        assertThat(drafts).isEmpty();
        assertThat(scenario.continueAfterFirstTouch(start, menu)).isSameAs(menu);
    }

    @Test
    void theVenuesOwnSystemGetsTheOrderOnceItIsSwitchedOn() {
        VenueSystem saby = new VenueSystem(new ExternalLunchOrderProvider.Result(true, "SABY", "ACCEPTED", "SABY-501", ""));
        providers.add(saby);
        say("/start lunch_simple_s2_p2_d20261006_t1300_r7f3a");
        say("Салат дня");

        OutgoingMessage placed = say("да");

        assertThat(saby.order.setCode()).isEqualTo("STARTER_MAIN");
        assertThat(saby.order.dishes()).extracting(BusinessLunchOrder.Item::dishCode).containsExactly("SALAD_OF_THE_DAY", "MAIN_OF_THE_DAY");
        assertThat(saby.order.source()).isEqualTo("CONCIERGE");
        assertThat(saby.order.conciergeRequestId()).isEqualTo("7f3a");
        assertThat(saby.order.startAt()).isEqualTo(TUESDAY_13_00);
        verify(tableReservationService).attachExternalId(77L, "SABY-501");
        assertThat(placed.actions()).contains("EXTERNAL_ORDER_SENT").doesNotContain("EXTERNAL_ORDER_MANUAL");
        // Saby has the order, the hostess still has the last word.
        assertThat(placed.text()).contains("Как только хостес ответит").doesNotContain("Saby");
        assertThat(placed.adminAlert().required()).isFalse();
        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().guestComment()).doesNotContain("вручную");
    }

    @Test
    void whenTheVenuesSystemRefusesTheTeamIsToldToEnterTheOrderByHand() {
        providers.add(new VenueSystem(new ExternalLunchOrderProvider.Result(false, "SABY", "PROVIDER_CONTRACT_NOT_IMPLEMENTED", "", "blocked")));
        say("/start lunch_simple_s2_p2_d20261006_t1300");
        say("Суп дня");

        OutgoingMessage placed = say("да");

        assertThat(placed.text()).contains("Заявку #77 на бизнес-ланч передал команде");
        assertThat(placed.actions()).contains("EXTERNAL_ORDER_MANUAL");
        AdminAlert alert = placed.adminAlert();
        assertThat(alert.required()).isTrue();
        assertThat(alert.chatId()).isEqualTo("100500");
        assertThat(alert.text()).contains("SABY не принял заказ. Внесите его вручную.", "Заявка #77", "PROVIDER_CONTRACT_NOT_IMPLEMENTED");
        verify(tableReservationService, never()).attachExternalId(anyLong(), anyString());
    }

    @Test
    void takesOnlyWhatIsALunch() {
        IncomingMessage lunch = telegram("хочу бизнес-ланч", null);
        IncomingMessage time = telegram("13:00", null);

        assertThat(scenario.supports(lunch, BotState.READY_FOR_DIALOG, lunch.text())).isTrue();
        // Inside another dialogue the word alone does not pull the guest out of it, a link from the Concierge does.
        assertThat(scenario.supports(lunch, BotState.TABLE_BOOKING_COLLECT_TIME, lunch.text())).isFalse();
        assertThat(scenario.supports(telegram("/start lunch_aeris", null), BotState.TABLE_BOOKING_COLLECT_TIME, "/start lunch_aeris")).isTrue();
        assertThat(scenario.supports(time, BotState.READY_FOR_DIALOG, time.text())).isFalse();
        assertThat(scenario.supports(time, BotState.BUSINESS_LUNCH_COLLECT_TIME, time.text())).isTrue();
        // An empty message, a voice note for example, is left to the gateway.
        assertThat(scenario.supports(telegram("", null), BotState.BUSINESS_LUNCH_COLLECT_TIME, "")).isFalse();

        ReflectionTestUtils.setField(scenario, "enabled", false);
        assertThat(scenario.supports(lunch, BotState.READY_FOR_DIALOG, lunch.text())).isFalse();
        assertThat(scenario.supports(time, BotState.BUSINESS_LUNCH_COLLECT_TIME, time.text())).isFalse();
    }

    @Test
    void aVenueWithoutALunchSaysSoAndReturnsToTheMenu() {
        OutgoingMessage reply = say("/start lunch_nowhere");

        assertThat(reply.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(reply.text()).contains("Бизнес-ланч для этого заведения пока не подключен.");
        assertThat(drafts).isEmpty();
    }

    private OutgoingMessage say(String text) {
        IncomingMessage incoming = telegram(text, null);
        BotState state = states.get(CHAT);
        assertThat(scenario.supports(incoming, state, text)).as("the scenario takes: " + text).isTrue();
        return scenario.handle(incoming, state, text);
    }

    private OutgoingMessage firstTouch(IncomingMessage incoming, BotState next, String action) {
        states.put(CHAT, next);
        return OutgoingMessage.of(incoming, "ответ первого касания", next.name(), false, false, false, false, AdminAlert.none(), List.of(action));
    }

    @SuppressWarnings("unchecked")
    private List<String> buttons(OutgoingMessage outgoing) {
        return ((List<List<String>>) outgoing.metadata().get("replyKeyboardRows")).stream().flatMap(List::stream).toList();
    }

    private IncomingMessage telegram(String text, String contactPhone) {
        return IncomingMessage.telegram(CHAT, CHAT, 1, 100, text, contactPhone, "Наталья", null, "guest", "ru", false, "test-correlation");
    }

    private final class InMemoryStates implements FSMStorage {
        @Override
        public void setState(Long chatId, BotState state) {
            states.put(chatId, state);
        }

        @Override
        public void clear(Long chatId) {
            states.remove(chatId);
        }

        @Override
        public BotState getState(Long chatId) {
            return states.get(chatId);
        }
    }

    private final class InMemoryDrafts extends BusinessLunchDraftStorage {
        private InMemoryDrafts() {
            super(null, null);
        }

        @Override
        public void save(Long chatId, Draft draft) {
            drafts.put(chatId, draft);
        }

        @Override
        public Optional<Draft> find(Long chatId) {
            return Optional.ofNullable(drafts.get(chatId));
        }

        @Override
        public void clear(Long chatId) {
            drafts.remove(chatId);
        }
    }

    /** Stands in for Saby with a fixed answer. */
    private static final class VenueSystem implements ExternalLunchOrderProvider {
        private final Result answer;
        private BusinessLunchOrder order;

        private VenueSystem(Result answer) {
            this.answer = answer;
        }

        @Override
        public String providerId() {
            return "SABY";
        }

        @Override
        public ExternalReservationStatus status() {
            return new ExternalReservationStatus("SABY", true, true, List.of(), "READY");
        }

        @Override
        public Result submit(BusinessLunchOrder submitted, String idempotencyKey) {
            order = submitted;
            return answer;
        }
    }
}
