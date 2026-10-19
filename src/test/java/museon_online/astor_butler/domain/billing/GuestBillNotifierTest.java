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
    void thePaymentLinkCarriesTheVenueTotalTheLinkAndTheBill() {
        notifier.setConciergeWebhook(webhook);
        when(webhook.announceNow(anyString(), anyLong(), any(), any())).thenReturn(true);

        assertThat(notifier.paymentLink(bill(null, 245050L), "https://pay.test/9")).isTrue();

        verify(webhook).announceNow(eq("PAYMENT_LINK"), eq(93L), isNull(), argThat(facts ->
                new BigDecimal("2450.50").equals(facts.get("totalRub"))
                        && "https://pay.test/9".equals(facts.get("paymentUrl"))
                        && Long.valueOf(5L).equals(facts.get("billId"))));
        verify(bot, never()).getIfAvailable();
    }

    @Test
    void aWholeTotalIsAWholeNumberOfRubles() {
        notifier.setConciergeWebhook(webhook);

        notifier.paymentLink(bill(null, 245000L), "https://pay.test/9");

        verify(webhook).announceNow(eq("PAYMENT_LINK"), eq(93L), isNull(), argThat(facts -> Long.valueOf(2450L).equals(facts.get("totalRub"))));
    }

    @Test
    void aTelegramBillDoesNotGoToTheConcierge() {
        notifier.setConciergeWebhook(webhook);

        notifier.paymentLink(bill(1773317437L, 245050L), "https://pay.test/9");

        verify(webhook, never()).announceNow(anyString(), anyLong(), any(), any());
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
        notifier.reviewPromptQueued(bill(1773317437L, 245050L));

        verify(webhook, never()).announceNow(anyString(), anyLong(), any(), any());
        verify(webhook, never()).announce(anyString(), anyLong(), any(), any());
    }

    @Test
    void aQueuedReviewOfAConciergeBillUsesTheQueueNotTheCallingThread() {
        notifier.setConciergeWebhook(webhook);
        when(webhook.announce(anyString(), anyLong(), any(), any())).thenReturn(true);

        assertThat(notifier.reviewPromptQueued(bill(null, 245050L))).isTrue();

        verify(webhook).announce(eq("REVIEW"), eq(93L), isNull(), argThat(facts -> Long.valueOf(5L).equals(facts.get("billId"))));
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

    @Test
    void butlersEstimateIsNeverToldAsTheTotal() {
        notifier.setConciergeWebhook(webhook);

        notifier.paymentLink(bill(null, null), "https://pay.test/9");

        verify(webhook).announceNow(eq("PAYMENT_LINK"), eq(93L), isNull(), argThat(facts -> !facts.containsKey("totalRub")
                && "https://pay.test/9".equals(facts.get("paymentUrl"))));
    }

    /** 16.10: a Concierge guest has no chat with the Butler's bot, even when the bill carries a chat id. */
    @Test
    void aConciergeOrderWithAChatIdStillGoesThroughTheConcierge() {
        notifier.setConciergeWebhook(webhook);
        when(webhook.announce(anyString(), anyLong(), any(), any())).thenReturn(true);
        GuestBill bill = bill(1773317437L, 245000L, "CONCIERGE");

        assertThat(GuestBillNotifier.concierge(bill)).isTrue();
        assertThat(notifier.reviewPromptQueued(bill)).isTrue();
        assertThat(notifier.paymentLinkQueued(bill, "https://pay.test/9")).isTrue();
        assertThat(notifier.paid(bill)).isFalse();

        verify(webhook).announce(eq("REVIEW"), eq(93L), isNull(), any());
        verify(webhook).announce(eq("PAYMENT_LINK"), eq(93L), isNull(), argThat(facts -> "https://pay.test/9".equals(facts.get("paymentUrl"))));
        verify(bot, never()).getIfAvailable();
    }

    @Test
    void aBotGuestAskingForTheBillGetsTheLinkInTheBotChat() {
        notifier.setConciergeWebhook(webhook);

        // Telegram is off in this test: the bot path is taken and sends nothing; the Concierge is never asked.
        assertThat(notifier.paymentLinkQueued(bill(1773317437L, 245000L), "https://pay.test/9")).isFalse();

        verify(webhook, never()).announce(anyString(), anyLong(), any(), any());
        verify(webhook, never()).announceNow(anyString(), anyLong(), any(), any());
    }

    private static GuestBill bill(Long chatId, Long venueAmountMinor) {
        return bill(chatId, venueAmountMinor, chatId == null ? "CONCIERGE" : "TELEGRAM");
    }

    private static GuestBill bill(Long chatId, Long venueAmountMinor, String source) {
        return new GuestBill(5L, chatId, chatId, "AERIS", GuestBillKind.BUSINESS_LUNCH, source, 93L, "SABY", "order-9",
                GuestBillStatus.ISSUED, null, 27000L, venueAmountMinor, "RUB", null, AT, null, null, AT, AT);
    }
}
