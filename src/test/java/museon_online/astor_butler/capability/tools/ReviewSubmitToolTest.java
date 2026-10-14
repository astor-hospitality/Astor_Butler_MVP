package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.domain.billing.VisitReviewService;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewSubmitToolTest {

    private VisitReviewService reviews;
    private ReviewSubmitTool tool;

    @BeforeEach
    void setUp() {
        reviews = mock(VisitReviewService.class);
        TableReservationRepository orders = mock(TableReservationRepository.class);
        when(orders.findOrder(41L)).thenReturn(Optional.of(CapabilityFixtures.order(41L, CapabilityFixtures.BUTLER_CHAT,
                TableReservationStatus.SEATED)));
        tool = new ReviewSubmitTool(reviews, new ReservationAccess(orders, mock(GuestVisitRepository.class), mock(VisitTurnRepository.class)));
    }

    @Test
    void secondReviewIsNotAllowed() {
        when(reviews.submit(41L, 5, "Всё было прекрасно")).thenReturn(true, false);

        CapabilityResult first = tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "stars", 5, "text", "Всё было прекрасно"));
        CapabilityResult second = tool.invoke(CapabilityFixtures.butler("k2"), Map.of("reservationId", 41, "stars", 5, "text", "Всё было прекрасно"));

        assertThat(first.ok()).isTrue();
        assertThat(first.facts()).containsEntry("accepted", true);
        assertThat(second.error()).isEqualTo(CapabilityResult.NOT_ALLOWED);
    }

    @Test
    void starsOutsideOneToFiveAreABadArgument() {
        assertThat(tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41, "stars", 6)).error())
                .isEqualTo(CapabilityResult.BAD_ARGUMENT);
        assertThat(tool.invoke(CapabilityFixtures.butler("k1"), Map.of("reservationId", 41)).error())
                .isEqualTo(CapabilityResult.BAD_ARGUMENT);
        verify(reviews, never()).submit(anyLong(), anyInt(), any());
    }
}
