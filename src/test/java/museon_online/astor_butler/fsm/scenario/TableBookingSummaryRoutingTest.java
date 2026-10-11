package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.consent.ConsentVaultService;
import museon_online.astor_butler.domain.identity.IdentityService;
import museon_online.astor_butler.domain.media.AerisMediaCatalog;
import museon_online.astor_butler.domain.semantic.IntentExampleRepository;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The summary of a table booking as the router sees it. The understanding service, the first touch and the booking
 * itself are real; every other scenario is willing to take whatever the router offers it, the way the real ones take
 * a birthday, a sparkling wine or the word "изменить". It is Friday 26.06.2026, 14:32 in Yekaterinburg.
 */
class TableBookingSummaryRoutingTest {

    private static final long CHAT = 1773317437L;
    private static final String SEND = "✅ Отправить заявку";
    private static final String CHANGE = "✏️ Изменить";
    private static final String SUMMARY = "TABLE_BOOKING_CONFIRMATION";

    private final Map<Long, BotState> states = new HashMap<>();
    private final Map<Long, TableBookingDraftStorage.Draft> drafts = new HashMap<>();
    private final TableReservationService tableReservationService = mock(TableReservationService.class);
    private final ConsentVaultService consentVaultService = mock(ConsentVaultService.class);
    private final IntentExampleRepository intentExampleRepository = mock(IntentExampleRepository.class);
    private final EventBookingScenario eventBookingScenario = mock(EventBookingScenario.class);
    private final ChangeCancelScenario changeCancelScenario = mock(ChangeCancelScenario.class);
    private final ManagerHelpScenario managerHelpScenario = mock(ManagerHelpScenario.class);
    private final SafePlayScenario safePlayScenario = mock(SafePlayScenario.class);
    private final MenuAssetsScenario menuAssetsScenario = mock(MenuAssetsScenario.class);
    private ScenarioRouter router;

    @BeforeEach
    void setUp() {
        BookingTimeProvider clock = new BookingTimeProvider(Clock.fixed(Instant.parse("2026-06-26T09:32:00Z"), BookingTimeProvider.VENUE_ZONE));
        InMemoryStates fsmStorage = new InMemoryStates();
        InMemoryDrafts draftStorage = new InMemoryDrafts();
        TableBookingDraftMerger draftMerger = new TableBookingDraftMerger(draftStorage, clock);
        ReflectionTestUtils.setField(draftMerger, "defaultVenueCode", "AERIS");
        TableBookingScenario booking = new TableBookingScenario(
                fsmStorage,
                draftStorage,
                tableReservationService,
                mock(AerisMediaCatalog.class),
                draftMerger,
                new TableBookingStepRegistry(),
                new BookingPhraseService(),
                clock,
                new VenueOpeningHours(),
                mock(IdentityService.class)
        );
        ReflectionTestUtils.setField(booking, "planPdfAssetCode", "AERIS_FLOOR_PLAN");
        ReflectionTestUtils.setField(booking, "managerTelegramId", 876857557L);
        ReflectionTestUtils.setField(booking, "hostessChatId", "-1004291419562");
        BusinessLunchScenario businessLunchScenario = mock(BusinessLunchScenario.class);
        when(businessLunchScenario.continueAfterFirstTouch(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        router = new ScenarioRouter(
                fsmStorage,
                new FirstTouchScenario(fsmStorage, consentVaultService, draftStorage, mock(ChangeCancelDraftStorage.class)),
                businessLunchScenario,
                booking,
                eventBookingScenario,
                changeCancelScenario,
                managerHelpScenario,
                mock(FeedbackScenario.class),
                mock(PreferenceScenario.class),
                mock(ConciergeScenario.class),
                safePlayScenario,
                mock(MerchScenario.class),
                menuAssetsScenario,
                mock(QuietGuideScenario.class),
                mock(ImpactMeterScenario.class),
                mock(SmartTipScenario.class),
                mock(HiddenHeartScenario.class),
                mock(ArtAuctionScenario.class),
                mock(MainMenuScenario.class),
                mock(RecoveryScenario.class),
                new GuestInputUnderstandingService(),
                intentExampleRepository
        );
        for (FsmScenario neighbour : List.of(eventBookingScenario, changeCancelScenario, managerHelpScenario, safePlayScenario, menuAssetsScenario)) {
            when(neighbour.supports(any(), any(), anyString(), any())).thenReturn(true);
            when(neighbour.handle(any(), any(), anyString(), any())).thenAnswer(invocation -> takenByAnotherScenario(invocation.getArgument(0)));
        }
        when(consentVaultService.hasGrantedPrivacyPolicy(CHAT)).thenReturn(true);
        when(tableReservationService.createReservation(any())).thenReturn(order());
        // Table 5 and two guests are chosen for Saturday 27.06; the time is the last question.
        drafts.put(CHAT, new TableBookingDraftStorage.Draft(
                "AERIS", null, null, LocalDate.of(2026, 6, 27), null, 2, "5", null, null, true, "Забронировать стол"));
        states.put(CHAT, BotState.TABLE_BOOKING_COLLECT_TIME);
    }

    @Test
    void aWishThatNamesABirthdayAndSparklingWineStaysWithTheBooking() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);

        OutgoingMessage wish = say("У нас день рождения, можно свечи и шампанское к приходу?");

        assertThat(wish.nextState()).isEqualTo(SUMMARY);
        assertThat(wish.text()).startsWith("Пожелание записал.")
                .contains("Гостей: 2\nПожелание: У нас день рождения, можно свечи и шампанское к приходу?");
        verify(eventBookingScenario, never()).handle(any(), any(), anyString(), any());
        verify(safePlayScenario, never()).handle(any(), any(), anyString(), any());
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aCorrectionSaidAsAChangeCorrectsTheSummaryAndNotAnotherBooking() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);

        // "Изменить время" is also how a guest asks to move a request that already exists.
        OutgoingMessage later = say("изменить время на 21:00");

        assertThat(later.nextState()).isEqualTo(SUMMARY);
        assertThat(later.text()).startsWith("Поправил.").contains("Стол 5\n27.06 в 21:00\nГостей: 2");
        verify(changeCancelScenario, never()).handle(any(), any(), anyString(), any());
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aPartyTypedWithADigitIsThePartyAndNotAnHour() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);

        OutgoingMessage four = say("на 4");

        assertThat(four.nextState()).isEqualTo(SUMMARY);
        assertThat(four.text()).startsWith("Поправил.").contains("Стол 5\n27.06 в 20:00\nГостей: 4");
    }

    @Test
    void anHourSaidTheShortWayIsTheEveningAsAtTheTimeStep() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);

        OutgoingMessage nine = say("в 9");

        assertThat(nine.nextState()).isEqualTo(SUMMARY);
        assertThat(nine.text()).startsWith("Поправил.").contains("Стол 5\n27.06 в 21:00\nГостей: 2");
    }

    @Test
    void aBookingAskedInOnePhraseGoesToTheSummaryAndThenToTheHostess() {
        drafts.remove(CHAT);
        states.put(CHAT, BotState.READY_FOR_DIALOG);

        OutgoingMessage summary = say("Хочу забронировать стол завтра в 20:00 на двоих у окна");

        assertThat(summary.nextState()).isEqualTo(SUMMARY);
        assertThat(summary.text()).contains("Стол: подберем свободный\n27.06 в 20:00\nГостей: 2\nПожелание: у окна\n");
        verify(tableReservationService, never()).createReservation(any());

        OutgoingMessage sent = say(SEND);

        assertThat(sent.actions()).contains("RESERVATION_CREATED");
        verify(tableReservationService).createReservation(any());
    }

    @Test
    void theChangeButtonStartsThisBookingOver() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);

        OutgoingMessage again = say(CHANGE);

        assertThat(again.nextState()).isEqualTo(BotState.TABLE_BOOKING_COLLECT_PARTY_SIZE.name());
        assertThat(again.text()).startsWith("Хорошо, соберем заново.");
        assertThat(drafts).doesNotContainKey(CHAT);
        verify(changeCancelScenario, never()).handle(any(), any(), anyString(), any());
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void theSendButtonCreatesTheRequestAndIsNotStoredAsAMisunderstoodPhrase() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);
        verify(tableReservationService, never()).createReservation(any());

        OutgoingMessage sent = say(SEND);

        assertThat(sent.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(sent.actions()).contains("RESERVATION_CREATED", "WAIT_HOSTESS_CONFIRMATION", "RETURN_MAIN_MENU");
        verify(tableReservationService).createReservation(any());
        verify(intentExampleRepository, never()).captureMiss(any(), any(), any(), any(), any(), any(), anyDouble());
    }

    @Test
    void aCallForTheTeamAtTheSummaryStillReachesTheTeam() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);

        say("менеджер");

        verify(managerHelpScenario).handle(any(), any(), anyString(), any());
        verify(tableReservationService, never()).createReservation(any());
        // The draft waits: nothing was sent and nothing was thrown away.
        assertThat(drafts).containsKey(CHAT);
    }

    @Test
    void aRestartAtTheSummarySendsNothing() {
        assertThat(say("20:00").nextState()).isEqualTo(SUMMARY);

        OutgoingMessage restarted = say("/start");

        assertThat(restarted.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(drafts).doesNotContainKey(CHAT);

        // The old summary is still on the screen, and so is its button.
        say(SEND);

        verify(tableReservationService, never()).createReservation(any());
    }

    private OutgoingMessage say(String text) {
        IncomingMessage incoming = IncomingMessage.telegram(CHAT, CHAT, 1, 100, text, null, "Наталья", "Поединенко", "guest", "ru", false, "test-correlation");
        return router.route(incoming, states.get(CHAT), text);
    }

    private OutgoingMessage takenByAnotherScenario(IncomingMessage incoming) {
        states.put(CHAT, BotState.READY_FOR_DIALOG);
        return OutgoingMessage.of(incoming, "ответ другого сценария", BotState.READY_FOR_DIALOG.name(), false, false, false, false, AdminAlert.none(), List.of("ANOTHER_SCENARIO"));
    }

    private TableReservationOrder order() {
        return new TableReservationOrder(
                44L, CHAT, CHAT, null, 5L, "5", "Table 5", null, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM",
                Instant.parse("2026-06-27T15:00:00Z"), Instant.parse("2026-06-27T17:00:00Z"), 2,
                "Наталья Поединенко", null, "Забронировать стол | 20:00", 876857557L, null, "-1004291419562", null,
                Instant.parse("2026-06-26T09:32:00Z"), Instant.parse("2026-06-26T09:32:00Z"));
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

    private final class InMemoryDrafts extends TableBookingDraftStorage {
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
}
