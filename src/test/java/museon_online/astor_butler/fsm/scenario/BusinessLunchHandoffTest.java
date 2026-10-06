package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLunchHandoffTest {

    @Test
    void readsEverythingTheConciergePutIntoTheTelegramLink() {
        BusinessLunchHandoff handoff = BusinessLunchHandoff.from(telegram("/start lunch_aeris_s2_p2_d20261007_t1300_r7f3a", Map.of()), "/start lunch_aeris_s2_p2_d20261007_t1300_r7f3a").orElseThrow();

        assertThat(handoff.venueCode()).isEqualTo("aeris");
        assertThat(handoff.setRef()).isEqualTo("2");
        assertThat(handoff.partySize()).isEqualTo(2);
        assertThat(handoff.date()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(handoff.time()).isEqualTo(LocalTime.of(13, 0));
        assertThat(handoff.requestId()).isEqualTo("7f3a");
    }

    @Test
    void theShortestLinkIsEnoughAndBrokenPartsAreLeftOut() {
        assertThat(BusinessLunchHandoff.fromStartLink("/start lunch")).contains(new BusinessLunchHandoff(null, null, null, null, null, null));
        assertThat(BusinessLunchHandoff.fromStartLink("/START Lunch_AERIS")).contains(new BusinessLunchHandoff("AERIS", null, null, null, null, null));
        // A day and a time that do not exist are not passed on; the dialogue will ask.
        assertThat(BusinessLunchHandoff.fromStartLink("/start lunch_aeris_d20261345_t2575_pxx_zzz"))
                .contains(new BusinessLunchHandoff("aeris", null, null, null, null, null));
    }

    @Test
    void anOrdinaryStartOrAnotherLinkIsNotALunch() {
        for (String text : new String[]{"/start", "/start promo_42", "/start lunchbox", "lunch_aeris", "бизнес-ланч", "", null}) {
            assertThat(BusinessLunchHandoff.fromStartLink(text)).as(String.valueOf(text)).isEmpty();
        }
    }

    @Test
    void readsTheSameFromAGatewayMessage() {
        Map<String, Object> payload = Map.of("concierge", Map.of(
                "scenario", "business_lunch",
                "venueCode", "AERIS",
                "setCode", "FULL",
                "partySize", 3,
                "date", "2026-10-08",
                "time", "12:30",
                "requestId", "c-118"
        ));

        BusinessLunchHandoff handoff = BusinessLunchHandoff.from(telegram("Бизнес-ланч", payload), "Бизнес-ланч").orElseThrow();

        assertThat(handoff).isEqualTo(new BusinessLunchHandoff("AERIS", "FULL", 3, LocalDate.of(2026, 10, 8), LocalTime.of(12, 30), "c-118"));
    }

    @Test
    void aPayloadForAnotherScenarioIsNotALunch() {
        assertThat(BusinessLunchHandoff.from(telegram("привет", Map.of("concierge", Map.of("scenario", "TABLE_BOOKING"))), "привет")).isEmpty();
        assertThat(BusinessLunchHandoff.from(telegram("привет", Map.of("concierge", "BUSINESS_LUNCH")), "привет")).isEmpty();
    }

    private IncomingMessage telegram(String text, Map<String, Object> payload) {
        return IncomingMessage.telegram(1773317437L, 1773317437L, 1, 100, text, null, "Наталья", null, "guest", "ru", false, "test-correlation", payload);
    }
}
