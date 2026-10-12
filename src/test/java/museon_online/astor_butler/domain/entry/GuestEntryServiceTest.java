package museon_online.astor_butler.domain.entry;

import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The entry log (#128): one row and one trace line per /start with the staff parameter, nothing otherwise, nothing with the flag off. */
class GuestEntryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    private final GuestEntryEventRepository repository = mock(GuestEntryEventRepository.class);
    private final TelegramSystemNotifier system = mock(TelegramSystemNotifier.class);

    @Test
    void aStartWithTheStaffParameterIsWrittenOnceAndTracedOnce() {
        when(repository.staffDisplayName("aeris", "anna")).thenReturn(Optional.of("Анна"));
        String text = "/start lunch_aeris_s2_hanna_n5";

        Optional<StaffHandoffLink> link = service(true).rememberStart(guest(text), text);

        assertThat(link).contains(new StaffHandoffLink("anna", "5", true));
        verify(repository, times(1)).insert("aeris", 1001L, "anna", "5", "STAFF_HANDOFF", NOW);
        verify(system, times(1)).sendSystemNotice("Вход гостя Наталья (chat 1001): привёл(а) Анна (anna), стол 5, бизнес-ланч");
    }

    @Test
    void aBareStartOrAnyOtherTextWritesNothing() {
        GuestEntryService service = service(true);
        for (String text : new String[]{"/start", "/restart", "/start lunch_aeris_p2_t1300", "привет", "", null}) {
            assertThat(service.rememberStart(guest(text), text)).as(String.valueOf(text)).isEmpty();
        }
        verifyNoInteractions(repository, system);
    }

    @Test
    void withTheFlagOffTheParameterIsIgnoredExactlyAsBefore() {
        GuestEntryService service = service(false);

        assertThat(service.rememberStart(guest("/start lunch_aeris_hanna"), "/start lunch_aeris_hanna")).isEmpty();
        assertThat(service.responsibleFor(1001L)).isEmpty();
        verifyNoInteractions(repository, system);
    }

    @Test
    void anUnknownIdAndTheHostStandAreWrittenAndNamedWithoutFailing() {
        when(repository.staffDisplayName(anyString(), anyString())).thenReturn(Optional.empty());
        GuestEntryService service = service(true);

        assertThat(service.rememberStart(guest("/start h_xyz_t7"), "/start h_xyz_t7")).contains(new StaffHandoffLink("xyz", "7", false));

        verify(repository).insert("aeris", 1001L, "xyz", "7", "STAFF_HANDOFF", NOW);
        verify(system).sendSystemNotice("Вход гостя Наталья (chat 1001): привёл(а) стойка/неизвестно (xyz), стол 7");
        assertThat(service.displayName("host")).isEqualTo("стойка хоста");
        assertThat(service.displayName("HOST")).isEqualTo("стойка хоста");
        assertThat(service.displayName("xyz")).isEqualTo("стойка/неизвестно");
        assertThat(service.displayName(null)).isEqualTo("стойка/неизвестно");
        assertThat(service.known("host")).isTrue();
        assertThat(service.known("xyz")).isFalse();
    }

    @Test
    void aFailedWriteLookupOrTraceNeverReachesTheGuest() {
        doThrow(new RuntimeException("db down")).when(repository).insert(any(), anyLong(), any(), any(), any(), any());
        when(repository.staffDisplayName(any(), any())).thenThrow(new RuntimeException("db down"));
        when(repository.latestForChat(anyLong(), any())).thenThrow(new RuntimeException("db down"));
        when(system.sendSystemNotice(any())).thenThrow(new RuntimeException("telegram down"));
        GuestEntryService service = service(true);

        assertThat(service.rememberStart(guest("/start lunch_aeris_hanna"), "/start lunch_aeris_hanna")).isPresent();
        assertThat(service.responsibleFor(1001L)).isEmpty();
        assertThat(service.displayName("anna")).isEqualTo("стойка/неизвестно");
        assertThat(service.known("anna")).isFalse();
    }

    @Test
    void theResponsibleStaffMemberIsTheLatestEntryWithinTheSessionWindow() {
        when(repository.latestForChat(1001L, NOW.minus(Duration.ofHours(6)))).thenReturn(Optional.of(
                new GuestEntryEvent(1L, "aeris", 1001L, "anna", "5", "STAFF_HANDOFF", NOW.minus(Duration.ofMinutes(30)))));
        when(repository.staffDisplayName("aeris", "anna")).thenReturn(Optional.of("Анна"));
        GuestEntryService service = service(true);

        assertThat(service.responsibleFor(1001L)).contains("Анна");
        assertThat(service.responsibleFor(1002L)).isEmpty();
        assertThat(service.responsibleFor(null)).isEmpty();
    }

    @Test
    void theTraceLineNamesTheGuestWithoutAPhone() {
        IncomingMessage byUsername = IncomingMessage.telegram(1001L, 1001L, 1, 100, "/start h_anna", "+79990000000", null, null, "guest", "ru", false, "test");
        assertThat(GuestEntryService.traceLine(byUsername, new StaffHandoffLink("anna", null, false), "Анна"))
                .isEqualTo("Вход гостя @guest (chat 1001): привёл(а) Анна (anna)")
                .doesNotContain("7999");
        IncomingMessage nameless = IncomingMessage.telegram(1001L, 1001L, 1, 100, "/start h_anna", null, null, null, null, "ru", false, "test");
        assertThat(GuestEntryService.traceLine(nameless, new StaffHandoffLink("host", "3", true), "стойка хоста"))
                .isEqualTo("Вход гостя гость (chat 1001): привёл(а) стойка хоста (host), стол 3, бизнес-ланч");
    }

    private GuestEntryService service(boolean enabled) {
        GuestEntryService service = new GuestEntryService(
                new StaffHandoffProperties(enabled, "aeris", 6, "astor_butler_bot", "AERIS"), repository, system);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return service;
    }

    private static IncomingMessage guest(String text) {
        return IncomingMessage.telegram(1001L, 1001L, 1, 100, text, null, "Наталья", null, "guest", "ru", false, "test");
    }
}
