package museon_online.astor_butler.domain.journey;

import museon_online.astor_butler.domain.billing.BillPayState;
import museon_online.astor_butler.domain.billing.GuestBill;
import museon_online.astor_butler.domain.billing.GuestBillKind;
import museon_online.astor_butler.domain.billing.GuestBillLine;
import museon_online.astor_butler.domain.billing.GuestBillRepository;
import museon_online.astor_butler.domain.billing.GuestBillStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchFixtures;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LastLunchServiceTest {

    private static final long CHAT = 421441838L;

    private final GuestBillRepository bills = mock(GuestBillRepository.class);
    private final LastLunchService service = new LastLunchService(bills);
    private final BusinessLunchOffer menu = BusinessLunchFixtures.aLaCarte();

    private static GuestBill bill(long id, Instant at) {
        return new GuestBill(id, CHAT, CHAT, "CARTE", GuestBillKind.BUSINESS_LUNCH, "TELEGRAM", 100 + id, null, null,
                GuestBillStatus.ISSUED, BillPayState.NOT_REPORTED, 0L, null, "RUB", "MENU", at, null, null, at, at);
    }

    private static GuestBillLine line(long billId, int no, String code, String title, int quantity) {
        return new GuestBillLine(no * 10L, billId, no, code, title, quantity, 0L, false);
    }

    @Test
    void takesDishesOfTheLatestLunchBill() {
        when(bills.findByChatId(CHAT, 10)).thenReturn(List.of(bill(2, Instant.parse("2026-10-06T08:00:00Z")),
                bill(1, Instant.parse("2026-09-29T08:00:00Z"))));
        when(bills.findLines(2L)).thenReturn(List.of(line(2, 1, "BORSCHT", "Борщ со сметаной", 1), line(2, 2, "CAPPUCCINO", "Капучино", 1)));

        LastLunch last = service.find(CHAT, menu).orElseThrow();

        assertThat(last.dishCodes()).containsExactly("BORSCHT", "CAPPUCCINO");
        assertThat(last.titles()).containsExactly("Борщ со сметаной", "Капучино");
    }

    @Test
    void repeatsThePortions() {
        when(bills.findByChatId(CHAT, 10)).thenReturn(List.of(bill(2, Instant.parse("2026-10-06T08:00:00Z"))));
        when(bills.findLines(2L)).thenReturn(List.of(line(2, 1, "CAPPUCCINO", "Капучино", 2)));

        LastLunch last = service.find(CHAT, menu).orElseThrow();

        assertThat(last.dishCodes()).containsExactly("CAPPUCCINO", "CAPPUCCINO");
        assertThat(last.titles()).containsExactly("Капучино × 2");
    }

    @Test
    void dropsDishesNoLongerOnTheMenu() {
        when(bills.findByChatId(CHAT, 10)).thenReturn(List.of(bill(2, Instant.parse("2026-10-06T08:00:00Z"))));
        when(bills.findLines(2L)).thenReturn(List.of(line(2, 1, "PEA_SOUP", "Гороховый суп", 1), line(2, 2, "BORSCHT", "Борщ со сметаной", 1)));

        assertThat(service.find(CHAT, menu).orElseThrow().dishCodes()).containsExactly("BORSCHT");
    }

    @Test
    void emptyWhenNothingFromLastTimeIsOnTheMenu() {
        when(bills.findByChatId(CHAT, 10)).thenReturn(List.of(bill(2, Instant.parse("2026-10-06T08:00:00Z"))));
        when(bills.findLines(2L)).thenReturn(List.of(line(2, 1, "PEA_SOUP", "Гороховый суп", 1)));

        assertThat(service.find(CHAT, menu)).isEmpty();
    }

    @Test
    void emptyForAGuestWithoutBills() {
        when(bills.findByChatId(CHAT, 10)).thenReturn(List.of());

        assertThat(service.find(CHAT, menu)).isEmpty();
    }

    @Test
    void emptyWithoutChat() {
        assertThat(service.find(null, menu)).isEqualTo(Optional.empty());
        verifyNoInteractions(bills);
    }
}
