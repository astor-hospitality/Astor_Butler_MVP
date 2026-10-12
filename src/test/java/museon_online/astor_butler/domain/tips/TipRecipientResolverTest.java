package museon_online.astor_butler.domain.tips;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.tip.StaffProfile;
import museon_online.astor_butler.domain.tip.TipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Who gets the tip: the waiter of the table with a link, or the venue's default recipient as before. */
class TipRecipientResolverTest {

    /** 2026-10-14 13:00 in Yekaterinburg (UTC+5): the shift day there is the 14th. */
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private static final LocalDate SHIFT_DAY = LocalDate.of(2026, 10, 14);

    private final TipsPersonalProperties properties = new TipsPersonalProperties();
    private final TipsPersonalRepository directory = mock(TipsPersonalRepository.class);
    private final TipRepository tips = mock(TipRepository.class);
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final TipRecipientResolver resolver =
            new TipRecipientResolver(properties, directory, tips, reservations, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void venueDefault() {
        properties.setEnabled(true);
        when(tips.findDefaultStaff(any())).thenReturn(Optional.of(new StaffProfile(
                1L, "AERIS", "Команда AERIS", "VENUE_TEAM", 876857557L, null, null, true, "{}", NOW, NOW)));
    }

    @Test
    void theAssignedWaiterWithALinkIsThePersonalRecipient() {
        when(directory.waiterOfTable("AERIS", "5", SHIFT_DAY)).thenReturn(Optional.of(waiter("https://example.test/tip/daniil")));

        TipRecipient recipient = resolver.forOrder(order("5", NOW.minusSeconds(1800)));

        assertThat(recipient).isEqualTo(new TipRecipient("Даниил", "https://example.test/tip/daniil", true, "daniil"));
        verifyNoInteractions(tips);
    }

    @Test
    void anAssignedWaiterWithoutALinkLeavesTheDefaultRecipient() {
        when(directory.waiterOfTable("AERIS", "5", SHIFT_DAY)).thenReturn(Optional.of(waiter(null)));

        TipRecipient recipient = resolver.forOrder(order("5", NOW.minusSeconds(1800)));

        assertThat(recipient).isEqualTo(TipRecipient.venue("Команда AERIS", null));
        assertThat(recipient.personal()).isFalse();
    }

    @Test
    void anUnassignedTableOrATablelessOrderGetsTheDefaultRecipient() {
        when(directory.waiterOfTable(anyString(), anyString(), any())).thenReturn(Optional.empty());

        assertThat(resolver.forOrder(order("7", NOW)).personal()).isFalse();
        assertThat(resolver.forOrder(order(null, NOW)).personal()).isFalse();
        assertThat(resolver.forOrder(null).personal()).isFalse();
        assertThat(resolver.forOrder(order("7", NOW)).name()).isEqualTo("Команда AERIS");
    }

    @Test
    void withTheFlagOffTheDirectoryIsNotEvenAsked() {
        properties.setEnabled(false);

        TipRecipient recipient = resolver.forOrder(order("5", NOW));

        assertThat(recipient).isEqualTo(TipRecipient.venue("Команда AERIS", null));
        assertThat(resolver.forChat(42L).personal()).isFalse();
        verifyNoInteractions(directory, reservations);
    }

    @Test
    void theChatsVisitTodayNamesTheTableAndAVisitLongAgoDoesNot() {
        when(reservations.findActiveOrdersByChatId(42L)).thenReturn(List.of(
                order("9", NOW.minusSeconds(3600 * 24)),   // yesterday: not this visit
                order("5", NOW.minusSeconds(1800))));      // sat down half an hour ago
        when(directory.waiterOfTable("AERIS", "5", SHIFT_DAY)).thenReturn(Optional.of(waiter("https://example.test/tip/daniil")));

        assertThat(resolver.forChat(42L).personal()).isTrue();
        verify(directory, never()).waiterOfTable(eq("AERIS"), eq("9"), any());

        when(reservations.findActiveOrdersByChatId(43L)).thenReturn(List.of(order("5", NOW.minusSeconds(3600 * 24))));
        assertThat(resolver.forChat(43L).personal()).isFalse();
        assertThat(resolver.forChat(null).personal()).isFalse();
    }

    @Test
    void aDirectoryOrReservationFailureFallsBackToTheDefaultRecipient() {
        when(directory.waiterOfTable(anyString(), anyString(), any())).thenThrow(new IllegalStateException("db down"));
        when(reservations.findActiveOrdersByChatId(any())).thenThrow(new IllegalStateException("db down"));

        assertThat(resolver.forOrder(order("5", NOW))).isEqualTo(TipRecipient.venue("Команда AERIS", null));
        assertThat(resolver.forChat(42L)).isEqualTo(TipRecipient.venue("Команда AERIS", null));
    }

    @Test
    void withoutAnyDefaultProfileThereIsNoRecipientAtAll() {
        when(tips.findDefaultStaff(any())).thenReturn(Optional.empty());

        assertThat(resolver.forOrder(null)).isEqualTo(TipRecipient.none());
    }

    private static TipStaffMember waiter(String link) {
        return new TipStaffMember("daniil", "Даниил", "WAITER", true, true, link, null);
    }

    private static TableReservationOrder order(String tableCode, Instant startAt) {
        return new TableReservationOrder(77L, 42L, 42L, null, tableCode == null ? null : 5L, tableCode,
                tableCode == null ? null : "Стол " + tableCode, null, null, TableReservationStatus.CONFIRMED, "TELEGRAM",
                startAt, startAt.plusSeconds(7200), 2, "Гость", null, null, null, null, null, null, startAt, startAt);
    }
}
