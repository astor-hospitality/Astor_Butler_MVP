package museon_online.astor_butler.domain.lunch;

import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.fsm.scenario.BookingTimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessLunchServiceTest {

    private static final long CHAT = 1773317437L;
    // Monday 2026-10-05, 14:00 in Yekaterinburg.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final Instant TUESDAY_13_00 = Instant.parse("2026-10-06T08:00:00Z");

    @Mock
    private TableReservationService tableReservationService;

    private final BookingTimeProvider timeProvider = new BookingTimeProvider(Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), BookingTimeProvider.VENUE_ZONE));
    private final BusinessLunchOffer offer = BusinessLunchFixtures.fullMenu();

    @Test
    void knowsWhenALunchCanBeHad() {
        BusinessLunchService service = service(List.of());

        assertThat(service.windowIssue(offer, TODAY.plusDays(1), LocalTime.of(13, 0))).isEmpty();
        assertThat(service.windowIssue(offer, TODAY.plusDays(1), LocalTime.of(12, 0))).isEmpty();
        assertThat(service.windowIssue(offer, TODAY.plusDays(1), null)).isEmpty();
        assertThat(service.windowIssue(offer, TODAY.plusDays(1), LocalTime.of(16, 0))).contains(BusinessLunchService.WindowIssue.OUTSIDE_LUNCH_HOURS);
        assertThat(service.windowIssue(offer, TODAY.plusDays(1), LocalTime.of(11, 59))).contains(BusinessLunchService.WindowIssue.OUTSIDE_LUNCH_HOURS);
        assertThat(service.windowIssue(offer, TODAY.plusDays(5), LocalTime.of(13, 0))).contains(BusinessLunchService.WindowIssue.NOT_A_LUNCH_DAY);
        assertThat(service.windowIssue(offer, TODAY.minusDays(1), null)).contains(BusinessLunchService.WindowIssue.ALREADY_PASSED);
        // Today it is 14:00: half past two is still ahead, one o'clock is gone.
        assertThat(service.windowIssue(offer, TODAY, LocalTime.of(14, 30))).isEmpty();
        assertThat(service.windowIssue(offer, TODAY, LocalTime.of(13, 0))).contains(BusinessLunchService.WindowIssue.ALREADY_PASSED);
        assertThat(service.windowIssue(offer, TODAY, LocalTime.of(14, 0))).contains(BusinessLunchService.WindowIssue.ALREADY_PASSED);
    }

    @Test
    void holdsATableAndLeavesTheOrderToTheStaffWhenNoExternalSystemIsOn() {
        BusinessLunchService service = service(List.of(new FakeProvider(false, false, null)));
        when(tableReservationService.createReservation(any())).thenReturn(BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2));

        BusinessLunchService.Placement placement = service.place(request("CONCIERGE", "один гость без лука"));

        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().venueCode()).isEqualTo("AERIS");
        assertThat(command.getValue().tableCode()).isNull();
        assertThat(command.getValue().seatingPreference()).isEqualTo("Бизнес-ланч");
        assertThat(command.getValue().requestedStartAt()).isEqualTo(TUESDAY_13_00);
        assertThat(command.getValue().requestedEndAt()).isEqualTo(TUESDAY_13_00.plusSeconds(90 * 60));
        assertThat(command.getValue().partySize()).isEqualTo(2);
        assertThat(command.getValue().guestComment()).isEqualTo(
                "Бизнес-ланч из Concierge: 2 × «Салат или суп + горячее», 650 ₽. Куриный бульон, Паста с томатами. Пожелание: один гость без лука. В Saby внести вручную.");
        assertThat(placement.alreadyPlaced()).isFalse();
        assertThat(placement.external().status()).isEqualTo("MANUAL_ENTRY");
        assertThat(placement.external().attempted()).isFalse();
        verify(tableReservationService, never()).attachExternalId(anyLong(), anyString());
    }

    @Test
    void handsTheStructuredOrderToTheExternalSystemAndKeepsItsNumber() {
        FakeProvider saby = new FakeProvider(true, true, new ExternalLunchOrderProvider.Result(true, "SABY", "ACCEPTED", "SABY-501", ""));
        BusinessLunchService service = service(List.of(saby));
        when(tableReservationService.createReservation(any())).thenReturn(BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2));

        BusinessLunchService.Placement placement = service.place(request("DIRECT", null));

        assertThat(saby.key).isEqualTo("astor-lunch-77");
        assertThat(saby.order.tableReservationId()).isEqualTo(77L);
        assertThat(saby.order.tableCode()).isEqualTo("4");
        assertThat(saby.order.setCode()).isEqualTo("STARTER_MAIN");
        assertThat(saby.order.setPriceRub()).isEqualTo(650);
        assertThat(saby.order.guests()).isEqualTo(2);
        // Two guests, so two portions of each dish of the set, and the set price twice.
        assertThat(saby.order.dishes()).containsExactly(
                new BusinessLunchOrder.Item("SOUP", "BROTH", "Куриный бульон", 2, null),
                new BusinessLunchOrder.Item("MAIN", "PASTA", "Паста с томатами", 2, null));
        assertThat(saby.order.totalRub()).isEqualTo(1300);
        assertThat(placement.external().accepted()).isTrue();
        verify(tableReservationService).attachExternalId(77L, "SABY-501");
        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().guestComment()).isEqualTo("Бизнес-ланч: 2 × «Салат или суп + горячее», 650 ₽. Куриный бульон, Паста с томатами.");
    }

    @Test
    void anOrderFromAMenuWithoutSetsCountsPortionsAndTheTotal() {
        FakeProvider saby = new FakeProvider(true, true, new ExternalLunchOrderProvider.Result(true, "SABY", "ACCEPTED", "SABY-502", ""));
        BusinessLunchService service = service(List.of(saby));
        when(tableReservationService.createReservation(any())).thenReturn(BusinessLunchFixtures.reservation(78, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2));

        service.place(new BusinessLunchService.Request(CHAT, CHAT, BusinessLunchFixtures.aLaCarte(), null, List.of("BORSCHT", "NICOISE", "BORSCHT", "MORS", "MORS"),
                2, TODAY.plusDays(1), LocalTime.of(13, 0), "Наталья", null, null, "CONCIERGE", "7f3a"));

        assertThat(saby.order.setCode()).isNull();
        assertThat(saby.order.dishes()).containsExactly(
                new BusinessLunchOrder.Item("SOUP", "BORSCHT", "Борщ со сметаной", 2, 270),
                new BusinessLunchOrder.Item("SALAD", "NICOISE", "Нисуаз", 1, 290),
                new BusinessLunchOrder.Item("DRINKS", "MORS", "Клюквенный морс", 2, 140));
        assertThat(saby.order.totalRub()).isEqualTo(1110);
        var command = forClass(TableReservationCommand.class);
        verify(tableReservationService).createReservation(command.capture());
        assertThat(command.getValue().guestComment()).isEqualTo("Бизнес-ланч из Concierge: Борщ со сметаной × 2, Нисуаз × 1, Клюквенный морс × 2. Итого 1110 ₽.");
        assertThat(command.getValue().partySize()).isEqualTo(2);
    }

    @Test
    void anOrderWithNoDishesIsRefused() {
        BusinessLunchService service = service(List.of());

        assertThatThrownBy(() -> service.place(new BusinessLunchService.Request(CHAT, CHAT, BusinessLunchFixtures.aLaCarte(), null, List.of(),
                2, TODAY.plusDays(1), LocalTime.of(13, 0), null, null, null, "DIRECT", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void aFailingExternalSystemDoesNotLoseTheGuestsRequest() {
        FakeProvider broken = new FakeProvider(true, true, null);
        BusinessLunchService service = service(List.of(broken));
        when(tableReservationService.createReservation(any())).thenReturn(BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2));

        BusinessLunchService.Placement placement = service.place(request("DIRECT", null));

        assertThat(placement.reservation().id()).isEqualTo(77L);
        assertThat(placement.external().accepted()).isFalse();
        assertThat(placement.external().attempted()).isTrue();
        assertThat(placement.external().status()).isEqualTo("PROVIDER_ERROR");
        verify(tableReservationService, never()).attachExternalId(anyLong(), anyString());
    }

    @Test
    void theSameGuestAndTimeTwiceHoldsOneTable() {
        FakeProvider saby = new FakeProvider(true, true, new ExternalLunchOrderProvider.Result(true, "SABY", "ACCEPTED", "SABY-501", ""));
        BusinessLunchService service = service(List.of(saby));
        when(tableReservationService.listActiveReservationsByChatId(CHAT))
                .thenReturn(List.of(BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2)));

        BusinessLunchService.Placement placement = service.place(request("CONCIERGE", null));

        assertThat(placement.alreadyPlaced()).isTrue();
        assertThat(placement.reservation().id()).isEqualTo(77L);
        assertThat(saby.order).isNull();
        verify(tableReservationService, never()).createReservation(any());
    }

    @Test
    void knowsALunchReservationByItsMarkEvenAfterTheSeatingWishWasReplaced() {
        BusinessLunchService service = service(List.of());
        var lunch = BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2);
        var moved = new museon_online.astor_butler.domain.booking.TableReservationOrder(lunch.id(), lunch.chatId(), lunch.telegramUserId(), lunch.userId(),
                lunch.tableId(), lunch.tableCode(), lunch.tableDisplayName(), lunch.preferredZone(), "у окна", lunch.status(), lunch.source(),
                lunch.requestedStartAt(), lunch.requestedEndAt(), lunch.partySize(), lunch.guestName(), lunch.guestPhone(),
                "Бизнес-ланч: Борщ со сметаной × 2. Итого 540 ₽. | Стол/зона изменены: у окна", lunch.managerTelegramId(), lunch.managerUserId(),
                lunch.hostessChatId(), lunch.sbisExternalId(), lunch.createdAt(), lunch.updatedAt());
        var ordinary = new museon_online.astor_butler.domain.booking.TableReservationOrder(lunch.id(), lunch.chatId(), lunch.telegramUserId(), lunch.userId(),
                lunch.tableId(), lunch.tableCode(), lunch.tableDisplayName(), lunch.preferredZone(), "у окна", lunch.status(), lunch.source(),
                lunch.requestedStartAt(), lunch.requestedEndAt(), lunch.partySize(), lunch.guestName(), lunch.guestPhone(),
                "Забронировать стол", lunch.managerTelegramId(), lunch.managerUserId(),
                lunch.hostessChatId(), lunch.sbisExternalId(), lunch.createdAt(), lunch.updatedAt());

        assertThat(service.offerOf(lunch)).contains(offer);
        assertThat(service.offerOf(moved)).contains(offer);
        assertThat(service.offerOf(ordinary)).isEmpty();
        assertThat(service.offerOf(null)).isEmpty();
    }

    @Test
    void refusesWhatTheOfferDoesNotAllow() {
        BusinessLunchService service = service(List.of());
        BusinessLunchService.Request ok = request("DIRECT", null);

        assertThatThrownBy(() -> service.place(new BusinessLunchService.Request(CHAT, CHAT, offer, "NO_SUCH_SET", ok.dishCodes(), 2, ok.date(), ok.time(), null, null, null, "DIRECT", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.place(new BusinessLunchService.Request(CHAT, CHAT, offer, ok.setCode(), ok.dishCodes(), 0, ok.date(), ok.time(), null, null, null, "DIRECT", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.place(new BusinessLunchService.Request(CHAT, CHAT, offer, ok.setCode(), ok.dishCodes(), 2, ok.date(), LocalTime.of(19, 0), null, null, null, "DIRECT", null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tableReservationService, never()).createReservation(any());
    }

    private BusinessLunchService.Request request(String source, String comment) {
        return new BusinessLunchService.Request(CHAT, CHAT, offer, "STARTER_MAIN", List.of("BROTH", "PASTA"), 2, TODAY.plusDays(1), LocalTime.of(13, 0),
                "Наталья", null, comment, source, "CONCIERGE".equals(source) ? "7f3a" : null);
    }

    private BusinessLunchService service(List<ExternalLunchOrderProvider> providers) {
        return new BusinessLunchService(tableReservationService, providers, timeProvider, new BusinessLunchCatalog(List.of(offer)));
    }

    /** Stands in for the venue's system. A null answer makes it throw, as a network failure would. */
    private static final class FakeProvider implements ExternalLunchOrderProvider {
        private final boolean enabled;
        private final boolean configured;
        private final Result answer;
        private BusinessLunchOrder order;
        private String key;

        private FakeProvider(boolean enabled, boolean configured, Result answer) {
            this.enabled = enabled;
            this.configured = configured;
            this.answer = answer;
        }

        @Override
        public String providerId() {
            return "SABY";
        }

        @Override
        public ExternalReservationStatus status() {
            return new ExternalReservationStatus("SABY", enabled, configured, List.of(), enabled ? "READY" : "DISABLED");
        }

        @Override
        public Result submit(BusinessLunchOrder submitted, String idempotencyKey) {
            order = submitted;
            key = idempotencyKey;
            if (answer == null) {
                throw new IllegalStateException("connection refused");
            }
            return answer;
        }
    }
}
