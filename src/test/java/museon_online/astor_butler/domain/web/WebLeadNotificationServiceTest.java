package museon_online.astor_butler.domain.web;

import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.telegram.adapter.TelegramAdminNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebLeadNotificationServiceTest {

    private final TelegramAdminNotifier telegramAdminNotifier = mock(TelegramAdminNotifier.class);
    private final WebLeadNotificationService service = new WebLeadNotificationService(telegramAdminNotifier, 200);

    private IncomingMessage incoming(WebSessionResolution session, String text, Map<String, Object> payload) {
        return new IncomingMessage(
                MessageChannel.WEB,
                session.externalUserId(),
                session.chatId(),
                null,
                null,
                null,
                text,
                null,
                null,
                null,
                null,
                null,
                false,
                "corr-42",
                Instant.parse("2026-08-01T06:00:00Z"),
                payload
        );
    }

    @Test
    void projectsWebsiteMessageToTelegramOperatorCardOffTheRequestThread() throws Exception {
        ReflectionTestUtils.setField(service, "adminChatEnabled", true);
        WebSessionResolution session = new WebSessionResolution(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "session-42",
                "web:anon:session-42",
                900000042L
        );
        IncomingMessage incoming = incoming(session, "Хочу фильм о заводе <важно>", Map.of(
                "site", "c3ag",
                "page", "/film",
                "referrer", "https://example.com/",
                "selectedVideo", Map.of("slug", "umekon-zavod", "title", "Сериал ЗАВОД"),
                "consent", Map.of("privacyAccepted", true, "policyVersion", "2026-06-02-local")
        ));
        OutgoingMessage outgoing = OutgoingMessage.of(incoming, "Принял", "WEB_LEAD_RECEIVED", false, false, false, false, null,
                List.of("WEB_LEAD_CAPTURED", "ADMIN_ALERT"));
        String requestThread = Thread.currentThread().getName();
        StringBuilder sendThread = new StringBuilder();
        when(telegramAdminNotifier.sendAnalytics(anyString())).thenAnswer(invocation -> {
            sendThread.append(Thread.currentThread().getName());
            return true;
        });

        service.project(session, incoming, outgoing);
        service.awaitIdle(java.time.Duration.ofSeconds(5));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramAdminNotifier).sendAnalytics(captor.capture());
        String card = captor.getValue();
        assertThat(card).contains("website lead");
        assertThat(card).contains("lead 11111111-1111-1111-1111-111111111111");
        assertThat(card).contains("message corr-42");
        assertThat(card).contains("time 2026-08-01T06:00:00Z");
        assertThat(card).contains("session session-42");
        assertThat(card).contains("chat 900000042");
        assertThat(card).contains("Хочу фильм о заводе &lt;важно&gt;");
        assertThat(card).contains("Selected video: Сериал ЗАВОД (umekon-zavod)");
        assertThat(card).contains("Next state: WEB_LEAD_RECEIVED");
        assertThat(card).contains("WEB_LEAD_CAPTURED, ADMIN_ALERT");
        assertThat(sendThread.toString()).isEqualTo("web-lead-notify").isNotEqualTo(requestThread);
    }

    @Test
    void skipsBlankMessages() {
        ReflectionTestUtils.setField(service, "adminChatEnabled", true);
        WebSessionResolution session = new WebSessionResolution(UUID.randomUUID(), "session", "web:anon:session", 1L);

        service.project(session, incoming(session, "  ", Map.of()), null);

        verifyNoInteractions(telegramAdminNotifier);
    }

    @Test
    void truncatesLongGuestTextSoTheCardFitsTelegram() throws Exception {
        ReflectionTestUtils.setField(service, "adminChatEnabled", true);
        WebSessionResolution session = new WebSessionResolution(UUID.randomUUID(), "session", "web:anon:session", 1L);
        String longText = "ж".repeat(4000);
        when(telegramAdminNotifier.sendAnalytics(anyString())).thenReturn(true);

        service.project(session, incoming(session, longText, Map.of("site", "c3ag")), null);
        service.awaitIdle(java.time.Duration.ofSeconds(5));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramAdminNotifier).sendAnalytics(captor.capture());
        assertThat(captor.getValue()).contains("ж".repeat(WebLeadNotificationService.MAX_GUEST_TEXT_CHARS) + "… [обрезано]");
        assertThat(captor.getValue()).doesNotContain("ж".repeat(WebLeadNotificationService.MAX_GUEST_TEXT_CHARS + 1));
        assertThat(captor.getValue().length()).isLessThan(4096);
    }

    @Test
    void guestFlowProjectsOnlyTheFirstMessageOfASessionAndFallbacks() throws Exception {
        ReflectionTestUtils.setField(service, "adminChatEnabled", true);
        WebSessionResolution first = new WebSessionResolution(UUID.randomUUID(), "s", "web:anon:s", 1L, true);
        WebSessionResolution later = new WebSessionResolution(UUID.randomUUID(), "s", "web:anon:s", 1L, false);
        IncomingMessage incoming = incoming(later, "Хочу стол", Map.of("site", "astor-butler"));
        OutgoingMessage normal = OutgoingMessage.of(incoming, "На какой день?", "TABLE_BOOKING_COLLECT_DATE", false, false, false, false, null, List.of());
        OutgoingMessage fallback = OutgoingMessage.of(incoming, "Передам администратору", "AI_FALLBACK", false, false, false, true, null, List.of("FALLBACK"));

        // Explicit answer: an unstubbed inline mock answered from the worker thread is not reliably counted.
        when(telegramAdminNotifier.sendAnalytics(anyString())).thenReturn(true);
        service.projectGuestFlow(later, incoming, normal);
        verifyNoInteractions(telegramAdminNotifier);

        service.projectGuestFlow(first, incoming(first, "Привет", Map.of("site", "astor-butler")), normal);
        service.projectGuestFlow(later, incoming, fallback);
        service.awaitIdle(java.time.Duration.ofSeconds(5));
        ArgumentCaptor<String> cards = ArgumentCaptor.forClass(String.class);
        verify(telegramAdminNotifier, times(2)).sendAnalytics(cards.capture());
        assertThat(cards.getAllValues().get(0)).contains("Привет");
        assertThat(cards.getAllValues().get(1)).contains("Fallback: true");
        assertThat(service.droppedCount()).isZero();
    }

    @Test
    void dropsCardsWhenTheQueueIsFullInsteadOfBlocking() throws Exception {
        WebLeadNotificationService small = new WebLeadNotificationService(telegramAdminNotifier, 1);
        ReflectionTestUtils.setField(small, "adminChatEnabled", true);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(telegramAdminNotifier.sendAnalytics(anyString())).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return true;
        });
        WebSessionResolution session = new WebSessionResolution(UUID.randomUUID(), "session", "web:anon:session", 1L);

        small.project(session, incoming(session, "первое — уйдёт в отправку", Map.of()), null);   // occupies the thread
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        small.project(session, incoming(session, "второе — ждёт в очереди", Map.of()), null);     // queue capacity 1
        long before = System.nanoTime();
        small.project(session, incoming(session, "третье — сбрасывается", Map.of()), null);       // overflow
        assertThat(System.nanoTime() - before).isLessThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(small.droppedCount()).isEqualTo(1);
        assertThat(small.queuedCount()).isEqualTo(1);

        release.countDown();
        small.awaitIdle(java.time.Duration.ofSeconds(5));
        verify(telegramAdminNotifier, times(2)).sendAnalytics(anyString());
        small.shutdown();
    }
}
