package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.capability.IdempotencyStore;
import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.booking.VenueOpeningHours;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyEvents;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class ReservationCreateToolTest {

    /** The venue's clock: 10:00 in Yekaterinburg on Friday 16.10.2026, before the 20.10 table of {@link #args()}. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T05:00:00Z"), ZoneOffset.UTC);

    private TableReservationService reservations;
    private GuestVisitRepository visits;
    private JourneyEvents journey;
    private LedgerWriter ledger;
    private ReservationCreateTool tool;

    /** A tool with a booking service and nothing else of note. */
    public static ReservationCreateTool tool(TableReservationService reservations) {
        GuestVisitRepository visits = mock(GuestVisitRepository.class);
        when(visits.findByReservation(anyLong())).thenReturn(Optional.empty());
        return new ReservationCreateTool(reservations, visits, mock(JourneyEvents.class), mock(LedgerWriter.class),
                new CapabilityProperties(), new GuestJourneyProperties(), new VenueOpeningHours(), CLOCK);
    }

    public static Map<String, Object> args() {
        Map<String, Object> args = new HashMap<>();
        args.put("date", "2026-10-20");
        args.put("time", "19:00");
        args.put("partySize", 2);
        args.put("guestName", "Анна Петрова");
        args.put("wish", "у окна");
        return args;
    }

    @BeforeEach
    void setUp() {
        reservations = mock(TableReservationService.class);
        visits = mock(GuestVisitRepository.class);
        journey = mock(JourneyEvents.class);
        ledger = mock(LedgerWriter.class);
        when(visits.findByReservation(anyLong())).thenReturn(Optional.empty());
        tool = new ReservationCreateTool(reservations, visits, journey, ledger, new CapabilityProperties(), new GuestJourneyProperties(),
                new VenueOpeningHours(), CLOCK);
    }

    @Test
    void conciergeBookingGetsTheConciergeChannel() {
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        when(visits.findByReservation(41L)).thenReturn(Optional.of(CapabilityFixtures.visit(7L, 41L, null, JourneyStage.REQUESTED)));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), args());

        ArgumentCaptor<TableReservationCommand> command = ArgumentCaptor.forClass(TableReservationCommand.class);
        verify(reservations).createReservation(command.capture());
        assertThat(command.getValue().channel()).isEqualTo(TableReservationCommand.CHANNEL_CONCIERGE);
        assertThat(command.getValue().chatId()).isNull();
        assertThat(command.getValue().venueCode()).isEqualTo("AERIS");
        assertThat(command.getValue().guestName()).isEqualTo("Анна Петрова");
        assertThat(command.getValue().guestPhone()).isEqualTo("+79120000060");
        assertThat(command.getValue().seatingPreference()).isEqualTo("у окна");
        assertThat(command.getValue().partySize()).isEqualTo(2);
        assertThat(command.getValue().requestedStartAt()).isEqualTo(CapabilityFixtures.START);
        assertThat(command.getValue().requestedEndAt()).isEqualTo(CapabilityFixtures.START.plus(Duration.ofMinutes(90)));
        assertThat(result.ok()).isTrue();
        assertThat(result.facts()).containsEntry("reservationId", 41L).containsEntry("visitId", 7L)
                .containsEntry("table", "Стол у окна").containsEntry("time", "19:00").containsEntry("date", "2026-10-20")
                .doesNotContainKey("guestPhone");
        verify(ledger).linkSession(CapabilityFixtures.SESSION, 7L);
    }

    @Test
    void theButlersAgentBooksForItsOwnChatOverTelegram() {
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));

        tool.invoke(CapabilityFixtures.butler("k1"), args());

        ArgumentCaptor<TableReservationCommand> command = ArgumentCaptor.forClass(TableReservationCommand.class);
        verify(reservations).createReservation(command.capture());
        assertThat(command.getValue().channel()).isEqualTo(TableReservationCommand.CHANNEL_TELEGRAM);
        assertThat(command.getValue().chatId()).isEqualTo(CapabilityFixtures.BUTLER_CHAT);
        verify(ledger, never()).linkSession(anyString(), anyLong());
    }

    @Test
    void neverConfirms() {
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null, TableReservationStatus.CONFIRMED));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), args());

        assertThat(result.facts()).containsEntry("status", "AWAITING_HOSTESS");
        verify(reservations, never()).confirm(any());
    }

    @Test
    void commitmentWithoutKeyIsABadArgument() {
        CapabilityProperties properties = new CapabilityProperties();
        properties.setEnabled(true);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(tool), mock(LedgerWriter.class), properties,
                mock(IdempotencyStore.class));

        CapabilityResult result = registry.invoke("reservation_create", CapabilityFixtures.concierge(null), args());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        verifyNoInteractions(reservations);
        assertThat(tool.commitment()).isTrue();
    }

    @Test
    void missingDateOrNameIsABadArgument() {
        Map<String, Object> noDate = args();
        noDate.remove("date");
        Map<String, Object> noName = args();
        noName.remove("guestName");
        CapabilityResult nameless = tool.invoke(new museon_online.astor_butler.capability.CapabilityContext("CONCIERGE", null,
                "c-1", null, "k1", null, null), noName);

        assertThat(tool.invoke(CapabilityFixtures.concierge("k1"), noDate).error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        assertThat(nameless.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        verifyNoInteractions(reservations);
    }

    @Test
    void withoutTheGuestsYesToThisTableNothingIsBooked() {
        GuestAgreement.Table later = new GuestAgreement.Table(CapabilityFixtures.TABLE.date(), LocalTime.of(20, 0), 2);
        Map<String, GuestAgreement> agreements = new java.util.LinkedHashMap<>();
        agreements.put("no agreement (a REST call)", null);
        agreements.put("nothing said yet", GuestAgreement.NONE);
        agreements.put("a question about the offered table", GuestAgreement.of("Есть стол?", List.of(CapabilityFixtures.TABLE)));
        agreements.put("a yes to another time", GuestAgreement.of("да", List.of(later)));
        agreements.put("a yes with nothing offered", GuestAgreement.of("да", List.of()));

        agreements.forEach((name, agreement) -> {
            for (var ctx : List.of(CapabilityFixtures.concierge("k1", agreement), CapabilityFixtures.butler("k1", agreement))) {
                CapabilityResult result = tool.invoke(ctx, args());

                assertThat(result.ok()).as(name).isFalse();
                assertThat(result.error()).as(name).isEqualTo(CapabilityResult.NOT_ALLOWED);
                // The table to put to the guest, so the model can ask for the yes.
                assertThat(result.facts()).as(name).containsEntry("reason", GuestAgreement.NEEDS_GUEST_YES)
                        .containsEntry("date", "2026-10-20").containsEntry("time", "19:00").containsEntry("partySize", 2);
            }
        });
        verifyNoInteractions(reservations, journey, ledger);
    }

    @Test
    void aBadArgumentIsNamedBeforeTheGuestsYesIsAskedFor() {
        Map<String, Object> noDate = args();
        noDate.remove("date");

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1", GuestAgreement.NONE), noDate);

        assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
        verifyNoInteractions(reservations);
    }

    @Test
    void aGuestWhoAlreadyHoldsATableThenGetsNoSecondOne() {
        Instant end = CapabilityFixtures.START.plus(Duration.ofMinutes(90));
        // 18:30 the same evening: crosses the 19:00 visit.
        Instant halfSix = CapabilityFixtures.START.minus(Duration.ofMinutes(30));
        when(reservations.findOverlappingReservation(CapabilityFixtures.BUTLER_CHAT, "AERIS", CapabilityFixtures.START, end))
                .thenReturn(Optional.of(CapabilityFixtures.order(40L, CapabilityFixtures.BUTLER_CHAT,
                        TableReservationStatus.CONFIRMED, halfSix)));
        when(reservations.findOverlappingSessionReservation(CapabilityFixtures.SESSION, 7L, "AERIS", CapabilityFixtures.START, end))
                .thenReturn(Optional.of(CapabilityFixtures.order(39L, null, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, halfSix)));
        CapabilityContext conciergeInVisit = new CapabilityContext(CapabilityContext.CONCIERGE, 9001L, CapabilityFixtures.SESSION,
                7L, "k2", "Анна", null, CapabilityFixtures.AGREED);

        CapabilityResult bot = tool.invoke(CapabilityFixtures.butler("k1"), args());
        CapabilityResult concierge = tool.invoke(conciergeInVisit, args());

        assertThat(bot.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(bot.facts()).isEqualTo(Map.of("reason", "overlapping", "reservationId", 40L, "date", "2026-10-20", "dateLabel", "вт 20.10",
                "time", "18:30"));
        assertThat(concierge.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(concierge.facts()).isEqualTo(Map.of("reason", "overlapping", "reservationId", 39L, "date", "2026-10-20", "dateLabel", "вт 20.10",
                "time", "18:30"));
        verify(reservations, never()).createReservation(any());
    }

    @Test
    void aSlotThatHasBegunOrIsOutsideTheHoursIsRefusedBeforeTheYes() {
        Map<String, Object> yesterday = args();
        yesterday.put("date", "2026-10-15");
        Map<String, Object> thisMorning = args();
        thisMorning.put("date", "2026-10-16");
        thisMorning.put("time", "09:30");
        // Saturday opens at 14:00.
        Map<String, Object> saturdayNoon = args();
        saturdayNoon.put("date", "2026-10-17");
        saturdayNoon.put("time", "12:00");

        CapabilityResult past = tool.invoke(CapabilityFixtures.concierge("k1"), yesterday);
        CapabilityResult begun = tool.invoke(CapabilityFixtures.butler("k2"), thisMorning);
        CapabilityResult closed = tool.invoke(CapabilityFixtures.concierge("k3", GuestAgreement.NONE), saturdayNoon);

        assertThat(past.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(past.facts()).isEqualTo(Map.of("reason", "already_passed", "date", "2026-10-15", "dateLabel", "чт 15.10",
                "time", "19:00"));
        assertThat(begun.facts()).containsEntry("reason", "already_passed");
        assertThat(closed.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(closed.facts()).isEqualTo(Map.of("reason", "closed", "date", "2026-10-17", "dateLabel", "сб 17.10",
                "time", "12:00", "hours", "с 14:00 до 04:00"));
        verifyNoInteractions(reservations);
    }

    @Test
    void aGuestWithNothingAtThatTimeIsBooked() {
        Instant end = CapabilityFixtures.START.plus(Duration.ofMinutes(90));
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));

        CapabilityResult bot = tool.invoke(CapabilityFixtures.butler("k1"), args());
        CapabilityResult concierge = tool.invoke(CapabilityFixtures.concierge("k2"), args());

        assertThat(bot.ok()).isTrue();
        assertThat(concierge.ok()).isTrue();
        // The bot's guest is its chat; the Concierge's one is its session, whatever chat id the Concierge sends.
        verify(reservations).findOverlappingReservation(CapabilityFixtures.BUTLER_CHAT, "AERIS", CapabilityFixtures.START, end);
        verify(reservations).findOverlappingSessionReservation(CapabilityFixtures.SESSION, null, "AERIS", CapabilityFixtures.START, end);
        verify(reservations, never()).findOverlappingReservation(eq(9001L), anyString(), any(), any());
        verify(reservations, times(2)).createReservation(any());
    }

    @Test
    void aBusyTableIsNotAllowedWithAReason() {
        when(reservations.createReservation(any())).thenThrow(new museon_online.astor_butler.api.common.ApiException(
                org.springframework.http.HttpStatus.CONFLICT, museon_online.astor_butler.api.common.ErrorCode.CONFLICT, "busy"));

        CapabilityResult result = tool.invoke(CapabilityFixtures.concierge("k1"), args());

        assertThat(result.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
        assertThat(result.facts()).containsEntry("reason", "table_busy");
    }
}
