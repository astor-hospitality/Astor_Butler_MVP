package museon_online.astor_butler.integration.concierge;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PAYMENT_LINK: the Butler's line for the Concierge's «Оплатить» button; the link rides in the facts, not the line. */
class ConciergeEventTest {

    @Test
    void thePaymentLineNamesTheVenueSumOnlyWhenThereIsOneAndNeverTheLink() {
        ConciergeEvent withSum = ConciergeEvent.of(ConciergeEvent.PAYMENT_LINK, 68L, 64L, "c-1",
                Map.of("totalRub", 2510L, "paymentUrl", "https://pay.example/68", "billId", 21L));
        ConciergeEvent withoutSum = ConciergeEvent.of(ConciergeEvent.PAYMENT_LINK, 68L, 64L, "c-1",
                Map.of("paymentUrl", "https://pay.example/68", "billId", 21L));

        assertThat(withSum.butlerText()).contains("2510 ₽").contains("«Оплатить»").doesNotContain("https://");
        assertThat(withoutSum.butlerText()).doesNotContain("₽").contains("«Оплатить»").doesNotContain("https://");
        assertThat(withSum.facts()).containsEntry("paymentUrl", "https://pay.example/68");
    }
}
