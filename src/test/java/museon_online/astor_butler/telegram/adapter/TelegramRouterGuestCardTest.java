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

    @Test
    void startDoesNotResendTheVisitingCardThatIsAlreadyPinned() throws Exception {
        TelegramChatViewService chatView = mock(TelegramChatViewService.class);
        TelegramRouter router = withPreview(router(new AstorLinksProperties(), chatView));
        org.mockito.Mockito.when(chatView.findPreviewMessageId(284069847L, "v-test")).thenReturn(77);
        org.telegram.telegrambots.meta.bots.AbsSender sender = mock(org.telegram.telegrambots.meta.bots.AbsSender.class);

        router.ensurePreview(guest("/start"), sender);

        org.mockito.Mockito.verify(sender, org.mockito.Mockito.never())
                .execute(org.mockito.ArgumentMatchers.any(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.class));
    }

    @Test
    void aGuestWithoutTheCardStillGetsItOnceToPin() throws Exception {
        TelegramChatViewService chatView = mock(TelegramChatViewService.class);
        TelegramRouter router = withPreview(router(new AstorLinksProperties(), chatView));
        org.mockito.Mockito.when(chatView.findPreviewMessageId(284069847L, "v-test")).thenReturn(null);
        org.telegram.telegrambots.meta.bots.AbsSender sender = mock(org.telegram.telegrambots.meta.bots.AbsSender.class);

        router.ensurePreview(guest("/start"), sender);

        org.mockito.Mockito.verify(sender)
                .execute(org.mockito.ArgumentMatchers.any(org.telegram.telegrambots.meta.api.methods.send.SendPhoto.class));
    }

    private static TelegramRouter withPreview(TelegramRouter router) {
        org.springframework.test.util.ReflectionTestUtils.setField(router, "previewEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(router, "previewVersion", "v-test");
        org.springframework.test.util.ReflectionTestUtils.setField(router, "previewAvatar",
                new org.springframework.core.io.ByteArrayResource(new byte[]{1, 2, 3}));
        return router;
    }

    private static museon_online.astor_butler.service.message.IncomingMessage guest(String text) {
        return museon_online.astor_butler.service.message.IncomingMessage.telegram(
                421441838L, 284069847L, 10, 20, text, null, "Наталья", "Тестова", "natalia_test", "ru", false, "20");
    }

    private static TelegramRouter router(AstorLinksProperties links) {
        return router(links, mock(TelegramChatViewService.class));
    }

    private static TelegramRouter router(AstorLinksProperties links, TelegramChatViewService chatView) {
        return new TelegramRouter(
                mock(TelegramExceptionHandler.class),
                mock(IdempotencyGuard.class),
                mock(MessageGatewayService.class),
                chatView,
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
