package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class LunchBillRecorderTest {

    private static final long CHAT = 1773317437L;
    private static final Instant START = Instant.parse("2026-10-06T08:00:00Z");

    private final BillingProperties properties = new BillingProperties();
    private GuestBillService bills;
    private LunchBillRecorder recorder;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        recorder = new LunchBillRecorder(properties, bills);
        properties.setEnabled(true);
    }

    @Test
    void writesNothingWhileBillingIsOff() {
        properties.setEnabled(false);

        recorder.placed(request(), aLaCarte(77L), ExternalLunchOrderProvider.Result.manualEntry());

        assertThat(bills.billsOf(CHAT, 10)).isEmpty();
    }

    @Test
    void pricesAnALaCarteLunchTheWayTheLunchItselfDoes() {
        BusinessLunchOrder order = aLaCarte(77L);

        recorder.placed(request(), order, ExternalLunchOrderProvider.Result.manualEntry());

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(bill.kind()).isEqualTo(GuestBillKind.BUSINESS_LUNCH);
        assertThat(bill.status()).isEqualTo(GuestBillStatus.ESTIMATED);
        assertThat(bill.tableReservationId()).isEqualTo(77L);
        assertThat(bill.source()).isEqualTo("DIRECT");
        assertThat(bill.estimateMinor()).isEqualTo(order.totalRub() * 100L);
        assertThat(bills.linesOf(bill.id())).extracting(GuestBillLine::itemCode, GuestBillLine::quantity, GuestBillLine::unitPriceMinor)
                .containsExactly(
                        tuple("BORSCHT", 2, 27_000L),
                        tuple("MEDOVIK", 1, 22_000L));
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type).containsExactly(BillOperationType.ESTIMATE_CREATED);
    }

    @Test
    void pricesASetOncePerGuestAndListsItsDishes() {
        BusinessLunchOrder order = set(78L);

        recorder.placed(request(), order, ExternalLunchOrderProvider.Result.manualEntry());

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(bill.estimateMinor()).isEqualTo(order.totalRub() * 100L);
        assertThat(bills.linesOf(bill.id())).extracting(GuestBillLine::itemCode, GuestBillLine::quantity, GuestBillLine::unitPriceMinor, GuestBillLine::included)
                .containsExactly(
                        tuple("STARTER_MAIN", 2, 65_000L, false),
                        tuple("BROTH", 2, null, true),
                        tuple("PASTA", 2, null, true));
    }

    @Test
    void issuesTheBillWhenTheVenueTakesTheOrder() {
        recorder.placed(request(), aLaCarte(77L), new ExternalLunchOrderProvider.Result(true, "SABY_PRESTO", "ACCEPTED", "order-1", ""));

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(bill.status()).isEqualTo(GuestBillStatus.ISSUED);
        assertThat(bill.externalProvider()).isEqualTo("SABY_PRESTO");
        assertThat(bill.externalOrderId()).isEqualTo("order-1");
    }

    @Test
    void journalsARefusalAndAnAnswerWithoutAnOrderId() {
        recorder.placed(request(), aLaCarte(77L), new ExternalLunchOrderProvider.Result(false, "SABY_PRESTO", "PROVIDER_CONTRACT_NOT_IMPLEMENTED", "", ""));
        recorder.placed(request(), aLaCarte(78L), new ExternalLunchOrderProvider.Result(true, "SABY_PRESTO", "ACCEPTED", " ", ""));

        List<GuestBill> all = bills.billsOf(CHAT, 10);
        assertThat(all).extracting(GuestBill::status).containsOnly(GuestBillStatus.ESTIMATED);
        assertThat(bills.historyOf(all.get(1).id()).getLast())
                .extracting(BillOperation::type, BillOperation::ok, BillOperation::errorCode)
                .containsExactly(BillOperationType.VENUE_SUBMIT_FAILED, false, "PROVIDER_CONTRACT_NOT_IMPLEMENTED");
        assertThat(bills.historyOf(all.get(0).id()).getLast().errorCode()).isEqualTo("ACCEPTED_WITHOUT_ID");
    }

    private static BusinessLunchService.Request request() {
        return new BusinessLunchService.Request(CHAT, CHAT, null, null, List.of(), 2, LocalDate.of(2026, 10, 6), LocalTime.of(13, 0),
                "Наталья", null, null, "DIRECT", null);
    }

    private static BusinessLunchOrder aLaCarte(long reservationId) {
        return new BusinessLunchOrder("AERIS", reservationId, null, START, START.plusSeconds(5400), 2, null, null, null,
                List.of(
                        new BusinessLunchOrder.Item("SOUP", "BORSCHT", "Борщ со сметаной", 2, 270),
                        new BusinessLunchOrder.Item("DESSERT", "MEDOVIK", "Медовик", 1, 220)),
                760, "Наталья", null, null, "DIRECT", null);
    }

    private static BusinessLunchOrder set(long reservationId) {
        return new BusinessLunchOrder("AERIS", reservationId, null, START, START.plusSeconds(5400), 2, "STARTER_MAIN", "Суп и горячее", 650,
                List.of(
                        new BusinessLunchOrder.Item("SOUP", "BROTH", "Куриный бульон", 2, null),
                        new BusinessLunchOrder.Item("MAIN", "PASTA", "Паста", 2, null)),
                1300, "Наталья", null, null, "DIRECT", null);
    }
}
