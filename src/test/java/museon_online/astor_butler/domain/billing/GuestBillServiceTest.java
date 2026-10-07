package museon_online.astor_butler.domain.billing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuestBillServiceTest {

    private static final long CHAT = 1773317437L;
    private static final String SABY = "SABY_PRESTO";

    private JdbcTemplate jdbc;
    private GuestBillService service;

    @BeforeEach
    void setUp() {
        jdbc = BillingTestDatabase.create();
        service = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
    }

    @Test
    void opensABillWithTheLinesItWasPricedBy() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));

        assertThat(bill.status()).isEqualTo(GuestBillStatus.ESTIMATED);
        assertThat(bill.payState()).isEqualTo(BillPayState.NOT_REPORTED);
        assertThat(bill.estimateMinor()).isEqualTo(76_000L);
        assertThat(bill.venueAmountMinor()).isNull();
        assertThat(bill.currency()).isEqualTo("RUB");
        assertThat(bill.createdAt()).isNotNull();
        assertThat(service.linesOf(bill.id()))
                .extracting(GuestBillLine::lineNo, GuestBillLine::itemCode, GuestBillLine::quantity, GuestBillLine::unitPriceMinor, GuestBillLine::included)
                .containsExactly(
                        tuple(1, "BORSCHT", 2, 27_000L, false),
                        tuple(2, "MEDOVIK", 1, 22_000L, false));
        assertThat(service.historyOf(bill.id()))
                .extracting(BillOperation::type, BillOperation::ok, BillOperation::actor, BillOperation::details)
                .containsExactly(tuple(BillOperationType.ESTIMATE_CREATED, true, BillActor.GUEST, "estimate=76000"));
    }

    @Test
    void keepsABillWithoutATotalWhenAPriceIsNotPublished() {
        GuestBill bill = service.open(draft(CHAT, 77L, OrderEstimate.of(List.of(OrderEstimate.Line.priced("SPECIAL", "Блюдо дня", 1, null)))));

        assertThat(bill.estimateMinor()).isNull();
        assertThat(service.historyOf(bill.id()).getFirst().details()).startsWith("no estimate");
    }

    @Test
    void opensOneBillPerReservation() {
        GuestBill first = service.open(draft(CHAT, 77L, twoDishes()));
        GuestBill again = service.open(draft(CHAT, 77L, twoDishes()));
        GuestBill other = service.open(draft(CHAT, 78L, twoDishes()));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(other.id()).isNotEqualTo(first.id());
        assertThat(service.historyOf(first.id())).hasSize(1);
    }

    @Test
    void showsAGuestOnlyTheirOwnBillsNewestFirst() {
        GuestBill first = service.open(draft(CHAT, 77L, twoDishes()));
        GuestBill second = service.open(draft(CHAT, 78L, twoDishes()));
        service.open(draft(CHAT + 1, 79L, twoDishes()));

        assertThat(service.billsOf(CHAT, 10)).extracting(GuestBill::id).containsExactly(second.id(), first.id());
        assertThat(service.billsOf(CHAT, 1)).extracting(GuestBill::id).containsExactly(second.id());
        assertThat(service.billsOf(null, 10)).isEmpty();
    }

    @Test
    void becomesIssuedOnceTheVenueNamesTheOrder() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));

        GuestBill issued = service.issued(bill.id(), SABY, "order-1");
        GuestBill repeated = service.issued(bill.id(), SABY, "order-1");

        assertThat(issued.status()).isEqualTo(GuestBillStatus.ISSUED);
        assertThat(issued.externalOrderId()).isEqualTo("order-1");
        assertThat(repeated).isEqualTo(issued);
        assertThat(service.historyOf(bill.id())).extracting(BillOperation::type)
                .containsExactly(BillOperationType.ESTIMATE_CREATED, BillOperationType.ISSUED_TO_VENUE);
        assertThatThrownBy(() -> service.issued(bill.id(), SABY, "order-2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.issued(bill.id(), SABY, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void staysAnEstimateWhenTheVenueDoesNotTakeTheOrder() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));

        service.venueSubmitFailed(bill.id(), SABY, "DISH_NOT_IN_SABY");

        assertThat(service.billsOf(CHAT, 10).getFirst().status()).isEqualTo(GuestBillStatus.ESTIMATED);
        BillOperation failure = service.historyOf(bill.id()).getLast();
        assertThat(failure.type()).isEqualTo(BillOperationType.VENUE_SUBMIT_FAILED);
        assertThat(failure.ok()).isFalse();
        assertThat(failure.errorCode()).isEqualTo("DISH_NOT_IN_SABY");
    }

    @Test
    void isPaidOnlyWhenTheVenueSaysSo() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));
        service.issued(bill.id(), SABY, "order-1");

        assertThat(service.venueReported(SABY, "order-1", BillPayState.UNPAID, 76_000L).orElseThrow().status()).isEqualTo(GuestBillStatus.ISSUED);
        assertThat(service.venueReported(SABY, "order-1", BillPayState.PREPAID_PARTIAL, null).orElseThrow().status()).isEqualTo(GuestBillStatus.ISSUED);
        assertThat(service.venueReported(SABY, "order-1", BillPayState.UNKNOWN, null).orElseThrow().status()).isEqualTo(GuestBillStatus.ISSUED);
        assertThat(service.venueReported(SABY, "order-1", null, null).orElseThrow().payState()).isEqualTo(BillPayState.UNKNOWN);

        GuestBill paid = service.venueReported(SABY, "order-1", BillPayState.PAID, null).orElseThrow();

        assertThat(paid.status()).isEqualTo(GuestBillStatus.PAID);
        assertThat(paid.payState()).isEqualTo(BillPayState.PAID);
        assertThat(paid.venueAmountMinor()).isEqualTo(76_000L);
        assertThat(service.historyOf(bill.id())).extracting(BillOperation::type, BillOperation::details).containsExactly(
                tuple(BillOperationType.ESTIMATE_CREATED, "estimate=76000"),
                tuple(BillOperationType.ISSUED_TO_VENUE, "provider=" + SABY),
                tuple(BillOperationType.PAY_STATE_CHANGED, "NOT_REPORTED -> UNPAID"),
                tuple(BillOperationType.VENUE_AMOUNT_REPORTED, "venue=76000"),
                tuple(BillOperationType.PAY_STATE_CHANGED, "UNPAID -> PREPAID_PARTIAL"),
                tuple(BillOperationType.PAY_STATE_CHANGED, "PREPAID_PARTIAL -> UNKNOWN"),
                tuple(BillOperationType.PAY_STATE_CHANGED, "UNKNOWN -> PAID"));
    }

    @Test
    void followsTheVenueBackWhenAPaymentIsUndone() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));
        service.issued(bill.id(), SABY, "order-1");
        service.venueReported(SABY, "order-1", BillPayState.PREPAID_FULL, 76_000L);

        GuestBill undone = service.venueReported(SABY, "order-1", BillPayState.UNPAID, null).orElseThrow();

        assertThat(undone.status()).isEqualTo(GuestBillStatus.ISSUED);
    }

    @Test
    void notesWhenTheVenueChargesAnotherSumThanTheEstimate() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));
        service.issued(bill.id(), SABY, "order-1");

        GuestBill reported = service.venueReported(SABY, "order-1", BillPayState.UNPAID, 80_000L).orElseThrow();

        assertThat(reported.estimateMinor()).isEqualTo(76_000L);
        assertThat(reported.venueAmountMinor()).isEqualTo(80_000L);
        assertThat(service.historyOf(bill.id()).getLast())
                .extracting(BillOperation::type, BillOperation::details)
                .containsExactly(BillOperationType.AMOUNT_MISMATCH, "estimate=76000, venue=80000");
    }

    @Test
    void writesNothingWhenTheVenueReportsNothingNew() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));
        service.issued(bill.id(), SABY, "order-1");
        service.venueReported(SABY, "order-1", BillPayState.UNPAID, 76_000L);
        int before = service.historyOf(bill.id()).size();

        service.venueReported(SABY, "order-1", BillPayState.UNPAID, 76_000L);
        service.venueReported(SABY, "order-1", BillPayState.UNPAID, null);

        assertThat(service.historyOf(bill.id())).hasSize(before);
        assertThat(service.venueReported(SABY, "order-of-someone-else", BillPayState.PAID, 1L)).isEmpty();
    }

    @Test
    void cancelsAnUnpaidBillAndLeavesAPaidOneToTheVenue() {
        GuestBill unpaid = service.open(draft(CHAT, 77L, twoDishes()));
        GuestBill paid = service.open(draft(CHAT, 78L, twoDishes()));
        service.issued(paid.id(), SABY, "order-2");
        service.venueReported(SABY, "order-2", BillPayState.PAID, 76_000L);

        GuestBill cancelled = service.cancel(unpaid.id(), BillActor.GUEST, "guest cancelled the lunch");

        assertThat(cancelled.status()).isEqualTo(GuestBillStatus.CANCELLED);
        assertThat(service.cancel(unpaid.id(), BillActor.GUEST, "again")).isEqualTo(cancelled);
        assertThat(service.historyOf(unpaid.id()).getLast())
                .extracting(BillOperation::type, BillOperation::actor)
                .containsExactly(BillOperationType.CANCELLED, BillActor.GUEST);
        assertThatThrownBy(() -> service.cancel(paid.id(), BillActor.GUEST, "changed my mind")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCancelledBillStaysCancelledButStillMirrorsTheVenue() {
        GuestBill bill = service.open(draft(CHAT, 77L, twoDishes()));
        service.issued(bill.id(), SABY, "order-1");
        service.cancel(bill.id(), BillActor.STAFF, "venue closed");

        GuestBill reported = service.venueReported(SABY, "order-1", BillPayState.PAID, 76_000L).orElseThrow();

        assertThat(reported.status()).isEqualTo(GuestBillStatus.CANCELLED);
        assertThat(reported.payState()).isEqualTo(BillPayState.PAID);
    }

    @Test
    void refusesADraftThatCannotBeABill() {
        assertThatThrownBy(() -> service.open(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.open(draft(null, 77L, twoDishes()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.venueSubmitFailed(404L, SABY, "X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theJournalCanOnlyBeAddedToAndRead() {
        List<String> publicMethods = Arrays.stream(BillOperationRepository.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .toList();

        assertThat(publicMethods).containsExactlyInAnyOrder("append", "findByBill");
    }

    private static BillDraft draft(Long chatId, Long reservationId, OrderEstimate estimate) {
        return new BillDraft(chatId, chatId, "aeris", GuestBillKind.BUSINESS_LUNCH, "direct", reservationId, "business-lunch:AERIS", estimate,
                java.time.Instant.parse("2026-10-06T09:30:00Z"));
    }

    private static OrderEstimate twoDishes() {
        return OrderEstimate.of(List.of(
                OrderEstimate.Line.priced("BORSCHT", "Борщ со сметаной", 2, 270),
                OrderEstimate.Line.priced("MEDOVIK", "Медовик", 1, 220)));
    }
}
