package museon_online.astor_butler.domain.ledger;

import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LedgerWriterTest {

    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");

    private final VisitTurnRepository turns = mock(VisitTurnRepository.class);
    private final GuestVisitRepository visits = mock(GuestVisitRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final LedgerProperties properties = new LedgerProperties();
    private LedgerWriter writer;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        writer = new LedgerWriter(turns, visits, properties, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void disabledWritesNothing() {
        properties.setEnabled(false);

        writer.write(draft(7L, "привет"));
        writer.message(1773317437L, TurnSide.GUEST, TurnChannel.BUTLER_TG, "привет");

        verifyNoInteractions(turns, visits, events);
    }

    @Test
    void repositoryFailureIsSwallowed() {
        when(turns.append(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> writer.write(draft(7L, "привет"))).doesNotThrowAnyException();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void phonesAreMaskedBeforeWriting() {
        when(turns.append(any())).thenReturn(41L);

        writer.write(draft(7L, "мой номер +79120000060"));

        ArgumentCaptor<VisitTurnDraft> written = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(turns).append(written.capture());
        assertThat(written.getValue().text()).isEqualTo("мой номер +7 912 ***-**-60");
        verify(events).publishEvent(new VisitTurnAppended(7L));
    }

    @Test
    void messageGoesToTheActiveVisitOfTheChat() {
        when(visits.activeByChat(1773317437L, NOW)).thenReturn(Optional.of(new GuestVisit(7L, 70L, 1773317437L, null, "AERIS",
                JourneyStage.CONFIRMED, null, null, 0, NOW, NOW, null)));

        writer.message(1773317437L, TurnSide.GUEST, TurnChannel.BUTLER_TG, "столик на двоих");

        verify(turns).append(new VisitTurnDraft(7L, null, TurnSide.GUEST, TurnChannel.BUTLER_TG, TurnKind.MESSAGE,
                "столик на двоих", null, null));
    }

    @Test
    void hiddenTurnKeepsNoText() {
        when(turns.append(any())).thenReturn(1L);

        writer.write(new VisitTurnDraft(null, "c-1", TurnSide.GUEST, TurnChannel.CONCIERGE_TG, TurnKind.MESSAGE, "секрет",
                java.util.Map.of("hidden", true), null));

        ArgumentCaptor<VisitTurnDraft> written = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(turns).append(written.capture());
        assertThat(written.getValue().text()).isNull();
    }

    private static VisitTurnDraft draft(Long visitId, String text) {
        return new VisitTurnDraft(visitId, null, TurnSide.GUEST, TurnChannel.BUTLER_TG, TurnKind.MESSAGE, text, null, null);
    }
}
