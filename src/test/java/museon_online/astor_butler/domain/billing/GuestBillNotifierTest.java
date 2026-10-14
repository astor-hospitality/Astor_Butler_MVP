package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.integration.concierge.ConciergeWebhook;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A Concierge bill (no Butler chat) hears about its link only through the Concierge webhook. */
class GuestBillNotifierTest {

    private static final Instant AT = Instant.parse("2026-10-14T09:00:00Z");

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TelegramBot> bot = mock(ObjectProvider.class);
    private final ConciergeWebhook webhook = mock(ConciergeWebhook.class);
    private final GuestBillNotifier notifier = new GuestBillNotifier(bot);

    @Test
    void billReadyCarriesTheTotalTheLinkAndTheBill() {
        notifier.setConciergeWebhook(webhook);
        when(webhook.announceNow(anyString(), anyLong(), any(), any())).thenReturn(true);

        assertThat(notifier.paymentLink(bill(null, 245050L), "https://pay.test/9")).isTrue();

        verify(webhook).announceNow(eq("BILL_READY"), eq(93L), isNull(), argThat(facts ->
                new BigDecimal("2450.50").equals(facts.get("totalRub"))
                        && "https://pay.test/9".equals(facts.get("paymentUrl"))
                        && Long.valueOf(5L).equals(facts.get("billId"))));
        verify(bot, never()).getIfAvailable();
    }

    @Test
    void aWholeTotalIsAWholeNumberOfRubles() {
        notifier.setConciergeWebhook(webhook);

        notifier.paymentLink(bill(null, 245000L), "https://pay.test/9");

        verify(webhook).announceNow(eq("BILL_READY"), eq(93L), isNull(), argThat(facts -> Long.valueOf(2450L).equals(facts.get("totalRub"))));
    }

    @Test
    void aTelegramBillDoesNotGoToTheConcierge() {
        notifier.setConciergeWebhook(webhook);

        notifier.paymentLink(bill(1773317437L, 245050L), "https://pay.test/9");

        verify(webhook, never()).announce(anyString(), anyLong(), any(), any());
    }

    @Test
    void aConciergeBillIsAskedForItsReviewThroughTheConcierge() {
        notifier.setConciergeWebhook(webhook);
        when(webhook.announceNow(anyString(), anyLong(), any(), any())).thenReturn(true);

        assertThat(notifier.reviewPrompt(bill(null, 245050L))).isTrue();

        verify(webhook).announceNow(eq("REVIEW"), eq(93L), isNull(), argThat(facts ->
                Long.valueOf(5L).equals(facts.get("billId")) && Long.valueOf(93L).equals(facts.get("reservationId"))));
        verify(bot, never()).getIfAvailable();
    }

    @Test
    void aTelegramReviewPromptDoesNotGoToTheConcierge() {
        notifier.setConciergeWebhook(webhook);

        notifier.reviewPrompt(bill(1773317437L, 245050L));

        verify(webhook, never()).announceNow(anyString(), anyLong(), any(), any());
    }

    @Test
    void reachesTheConciergeOnlyForALinkedSession() {
        assertThat(notifier.reachesConcierge(bill(null, 1L))).isFalse();
        notifier.setConciergeWebhook(webhook);
        when(webhook.reaches(93L)).thenReturn(true);

        assertThat(notifier.reachesConcierge(bill(null, 1L))).isTrue();
        assertThat(notifier.reachesConcierge(bill(1773317437L, 1L))).isFalse();
    }

    private static GuestBill bill(Long chatId, Long venueAmountMinor) {
        return new GuestBill(5L, chatId, chatId, "AERIS", GuestBillKind.BUSINESS_LUNCH, "CONCIERGE", 93L, "SABY", "order-9",
                GuestBillStatus.ISSUED, null, 27000L, venueAmountMinor, "RUB", null, AT, null, null, AT, AT);
    }
}
