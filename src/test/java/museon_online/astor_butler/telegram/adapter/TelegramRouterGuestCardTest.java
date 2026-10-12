package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.config.AstorLinksProperties;
import museon_online.astor_butler.domain.billing.HostessBillRequestService;
import museon_online.astor_butler.domain.billing.VisitReviewService;
import museon_online.astor_butler.domain.booking.BookingMorningCheckService;
import museon_online.astor_butler.domain.booking.HostessBookingEditService;
import museon_online.astor_butler.domain.booking.HostessReservationApprovalService;
import museon_online.astor_butler.fsm.core.idempotency.IdempotencyGuard;
import museon_online.astor_butler.service.message.MessageGatewayService;
import museon_online.astor_butler.telegram.exeption.TelegramExceptionHandler;
import museon_online.astor_butler.telegram.voice.TelegramVoiceReplyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** The pinned AERIS guest card links the guide from {@code astor.links.guest-guide} (#86). */
class TelegramRouterGuestCardTest {

    @Test
    void guestCardLinksTheConfiguredGuestGuide() {
        AstorLinksProperties links = new AstorLinksProperties();
        links.setGuestGuide("https://example.test/guest.html");

        assertThat(router(links).previewText())
                .contains("<a href=\"https://example.test/guest.html\">Короткая инструкция гостя</a>")
                .doesNotContain("michaelwelly.github.io");
    }

    @Test
    void guestCardDefaultsToTheOrganisationGithubPage() {
        assertThat(router(new AstorLinksProperties()).previewText())
                .contains("<a href=\"https://astor-hospitality.github.io/Astor_Butler_MVP/docs/guest-guide.html\">Короткая инструкция гостя</a>");
    }

    private static TelegramRouter router(AstorLinksProperties links) {
        return new TelegramRouter(
                mock(TelegramExceptionHandler.class),
                mock(IdempotencyGuard.class),
                mock(MessageGatewayService.class),
                mock(TelegramChatViewService.class),
                mock(TelegramVoiceEnrichmentGuard.class),
                mock(HostessReservationApprovalService.class),
                mock(TelegramMediaSender.class),
                mock(VisitReviewService.class),
                mock(BookingMorningCheckService.class),
                mock(HostessBillRequestService.class),
                mock(HostessBookingEditService.class),
                mock(TelegramVoiceReplyService.class),
                links
        );
    }
}
