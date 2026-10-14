package museon_online.astor_butler.domain.tips;

import museon_online.astor_butler.domain.tip.StaffProfile;
import museon_online.astor_butler.domain.tip.TipOrder;
import museon_online.astor_butler.domain.tip.TipOrderCommand;
import museon_online.astor_butler.domain.tip.TipOrderStatus;
import museon_online.astor_butler.domain.tip.TipRepository;
import museon_online.astor_butler.domain.tip.TipService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Where TipService.createDraft puts the recipient the resolver names, and when it does not ask. */
class TipServicePersonalRecipientTest {

    private static final Instant AT = Instant.parse("2026-10-14T08:00:00Z");

    private final TipRepository repository = mock(TipRepository.class);
    private final TipRecipientResolver resolver = mock(TipRecipientResolver.class);
    private final TipService service = new TipService(repository);

    @Test
    void thePersonalRecipientGoesIntoTheDraftByNameAndLinkWithoutAStaffProfile() {
        service.setRecipientResolver(resolver);
        when(resolver.enabled()).thenReturn(true);
        when(resolver.forChat(42L, "AERIS")).thenReturn(new TipRecipient("Даниил", "https://example.test/tip/daniil", true, "daniil"));
        when(repository.createDraft(any(), isNull(), eq("https://example.test/tip/daniil"))).thenReturn(order("Даниил", "https://example.test/tip/daniil"));

        TipOrder order = service.createDraft(command(null));

        ArgumentCaptor<TipOrderCommand> stored = ArgumentCaptor.forClass(TipOrderCommand.class);
        verify(repository).createDraft(stored.capture(), isNull(), eq("https://example.test/tip/daniil"));
        assertThat(stored.getValue().staffDisplayName()).isEqualTo("Даниил");
        assertThat(stored.getValue().staffId()).isNull();
        assertThat(stored.getValue().amountMinor()).isEqualTo(50_000L);
        assertThat(order.sbpUrl()).isEqualTo("https://example.test/tip/daniil");
        verify(repository, never()).findDefaultStaff(any());
    }

    @Test
    void withoutAPersonalRecipientTheDefaultProfileIsUsedAsBefore() {
        service.setRecipientResolver(resolver);
        when(resolver.enabled()).thenReturn(true);
        when(resolver.forChat(42L, "AERIS")).thenReturn(TipRecipient.venue("Команда AERIS", null));
        StaffProfile team = new StaffProfile(1L, "AERIS", "Команда AERIS", "VENUE_TEAM", 1L, null, null, true, "{}", AT, AT);
        when(repository.findDefaultStaff("AERIS")).thenReturn(Optional.of(team));
        when(repository.createDraft(any(), eq(team))).thenReturn(order("Команда AERIS", null));

        TipOrder order = service.createDraft(command(null));

        assertThat(order.staffDisplayName()).isEqualTo("Команда AERIS");
        verify(repository).createDraft(any(), eq(team));
    }

    @Test
    void withTheResolverOffOrAbsentOrARecipientNamedByTheCallerTheResolverIsNotAsked() {
        StaffProfile team = new StaffProfile(1L, "AERIS", "Команда AERIS", "VENUE_TEAM", 1L, null, null, true, "{}", AT, AT);
        when(repository.findDefaultStaff("AERIS")).thenReturn(Optional.of(team));
        when(repository.findStaff(7L)).thenReturn(Optional.of(team));
        when(repository.createDraft(any(), eq(team))).thenReturn(order("Команда AERIS", null));

        service.createDraft(command(null));                       // no resolver at all

        service.setRecipientResolver(resolver);
        when(resolver.enabled()).thenReturn(false);
        service.createDraft(command(null));                       // flag off

        when(resolver.enabled()).thenReturn(true);
        service.createDraft(command(7L));                         // the API named a staff profile

        verify(resolver, never()).forChat(any(), any());
        verify(repository, org.mockito.Mockito.times(3)).createDraft(any(), eq(team));
    }

    @Test
    void aDraftForAReservationNeedsNoChatAndTakesTheWaiterOfItsTable() {
        service.setRecipientResolver(resolver);
        when(resolver.enabled()).thenReturn(true);
        museon_online.astor_butler.domain.booking.TableReservationOrder reservation =
                org.mockito.Mockito.mock(museon_online.astor_butler.domain.booking.TableReservationOrder.class);
        when(resolver.forOrder(reservation, "AERIS")).thenReturn(new TipRecipient("Даниил", "https://example.test/tip/daniil", true, "daniil"));
        when(repository.createDraft(any(), isNull(), eq("https://example.test/tip/daniil"))).thenReturn(order("Даниил", "https://example.test/tip/daniil"));
        TipOrderCommand withoutChat = new TipOrderCommand(null, null, null, "AERIS", null, "Даниил", 50_000L, "RUB", "Наталья", null);

        service.createDraftFor(reservation, withoutChat);

        ArgumentCaptor<TipOrderCommand> stored = ArgumentCaptor.forClass(TipOrderCommand.class);
        verify(repository).createDraft(stored.capture(), isNull(), eq("https://example.test/tip/daniil"));
        assertThat(stored.getValue().chatId()).isNull();
        verify(resolver, never()).forChat(any(), any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createDraftFor(reservation,
                        new TipOrderCommand(null, null, null, "AERIS", null, "Даниил", 0L, "RUB", "Наталья", null)))
                .isInstanceOf(museon_online.astor_butler.api.common.ApiException.class);
    }

    private static TipOrderCommand command(Long staffId) {
        return new TipOrderCommand(42L, 42L, null, "AERIS", staffId, null, 50_000L, "RUB", "Наталья", "500");
    }

    private static TipOrder order(String name, String link) {
        return new TipOrder(55L, 42L, 42L, null, "AERIS", link == null ? 1L : null, name, TipOrderStatus.AWAITING_GUEST_CONFIRMATION,
                "TELEGRAM", 50_000L, "RUB", "Наталья", "500", link, null, AT, AT);
    }
}
