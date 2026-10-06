package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchFixtures;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrderListener;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import museon_online.astor_butler.fsm.scenario.BookingTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The recorder is driven the way production drives it: by a lunch placed through {@link BusinessLunchService}. */
class LunchBillRecorderTest {

    private static final long CHAT = 1773317437L;
    // Monday 2026-10-05, 14:00 in Yekaterinburg; the lunch is for Tuesday 13:00.
    private static final LocalDate TOMORROW = LocalDate.of(2026, 10, 6);
    private static final Instant TUESDAY_13_00 = Instant.parse("2026-10-06T08:00:00Z");

    private final BillingProperties properties = new BillingProperties();
    private final TableReservationService tableReservationService = mock(TableReservationService.class);
    private final BookingTimeProvider timeProvider = new BookingTimeProvider(Clock.fixed(Instant.parse("2026-10-05T09:00:00Z"), BookingTimeProvider.VENUE_ZONE));
    private GuestBillService bills;
    private LunchBillRecorder recorder;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        recorder = new LunchBillRecorder(properties, bills);
        properties.setEnabled(true);
        when(tableReservationService.createReservation(any()))
                .thenReturn(BusinessLunchFixtures.reservation(77, CHAT, TUESDAY_13_00, TUESDAY_13_00.plusSeconds(5400), 2));
    }

    @Test
    void writesNothingWhileBillingIsOff() {
        properties.setEnabled(false);

        BusinessLunchService.Placement placement = place(aLaCarte(), null);

        assertThat(placement.order()).isNotNull();
        assertThat(bills.billsOf(CHAT, 10)).isEmpty();
    }

    @Test
    void pricesAnALaCarteLunchTheWayTheLunchItselfDoes() {
        BusinessLunchOrder order = place(aLaCarte(), null).order();

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(bill.kind()).isEqualTo(GuestBillKind.BUSINESS_LUNCH);
        assertThat(bill.status()).isEqualTo(GuestBillStatus.ESTIMATED);
        assertThat(bill.venueCode()).isEqualTo("CARTE");
        assertThat(bill.tableReservationId()).isEqualTo(77L);
        assertThat(bill.source()).isEqualTo("DIRECT");
        assertThat(order.totalRub()).isEqualTo(1110);
        assertThat(bill.estimateMinor()).isEqualTo(111_000L);
        assertThat(bills.linesOf(bill.id())).extracting(GuestBillLine::itemCode, GuestBillLine::quantity, GuestBillLine::unitPriceMinor, GuestBillLine::included)
                .containsExactly(
                        tuple("BORSCHT", 2, 27_000L, false),
                        tuple("NICOISE", 1, 29_000L, false),
                        tuple("MORS", 2, 14_000L, false));
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type).containsExactly(BillOperationType.ESTIMATE_CREATED);
    }

    @Test
    void pricesASetOncePerGuestAndListsItsDishes() {
        BusinessLunchOrder order = place(set(), null).order();

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(order.totalRub()).isEqualTo(order.setPriceRub() * 2);
        assertThat(bill.estimateMinor()).isEqualTo(order.totalRub() * 100L);
        assertThat(bills.linesOf(bill.id())).extracting(GuestBillLine::itemCode, GuestBillLine::quantity, GuestBillLine::unitPriceMinor, GuestBillLine::included)
                .containsExactly(
                        tuple("STARTER_MAIN", 2, order.setPriceRub() * 100L, false),
                        tuple("BROTH", 2, null, true),
                        tuple("PASTA", 2, null, true));
    }

    @Test
    void issuesTheBillWhenTheVenueTakesTheOrder() {
        place(aLaCarte(), new ExternalLunchOrderProvider.Result(true, "SABY", "ACCEPTED", "SABY-501", ""));

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(bill.status()).isEqualTo(GuestBillStatus.ISSUED);
        assertThat(bill.externalProvider()).isEqualTo("SABY");
        assertThat(bill.externalOrderId()).isEqualTo("SABY-501");
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type)
                .containsExactly(BillOperationType.ESTIMATE_CREATED, BillOperationType.ISSUED_TO_VENUE);
    }

    @Test
    void journalsARefusalAndKeepsTheBillAnEstimate() {
        place(aLaCarte(), new ExternalLunchOrderProvider.Result(false, "SABY", "DISH_NOT_IN_SABY", "", ""));

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(bill.status()).isEqualTo(GuestBillStatus.ESTIMATED);
        assertThat(bills.historyOf(bill.id()).getLast())
                .extracting(BillOperation::type, BillOperation::ok, BillOperation::errorCode)
                .containsExactly(BillOperationType.VENUE_SUBMIT_FAILED, false, "DISH_NOT_IN_SABY");
    }

    @Test
    void anAnswerWithoutAnOrderIdIsNotAnIssuedBill() {
        place(aLaCarte(), new ExternalLunchOrderProvider.Result(true, "SABY", "ACCEPTED", " ", ""));

        GuestBill bill = bills.billsOf(CHAT, 10).getFirst();
        assertThat(bill.status()).isEqualTo(GuestBillStatus.ESTIMATED);
        assertThat(bills.historyOf(bill.id()).getLast().errorCode()).isEqualTo("ACCEPTED_WITHOUT_ID");
    }

    /** Places a lunch with the recorder listening. A null answer means no external system is switched on. */
    private BusinessLunchService.Placement place(BusinessLunchService.Request request, ExternalLunchOrderProvider.Result answer) {
        List<ExternalLunchOrderProvider> providers = answer == null ? List.of() : List.of(new AnsweringProvider(answer));
        BusinessLunchService service = new BusinessLunchService(tableReservationService, providers, timeProvider, new BusinessLunchCatalog(List.of(request.offer())));
        ReflectionTestUtils.setField(service, "listeners", List.<BusinessLunchOrderListener>of(recorder));
        return service.place(request);
    }

    private static BusinessLunchService.Request aLaCarte() {
        return request(BusinessLunchFixtures.aLaCarte(), null, List.of("BORSCHT", "NICOISE", "BORSCHT", "MORS", "MORS"));
    }

    private static BusinessLunchService.Request set() {
        return request(BusinessLunchFixtures.fullMenu(), "STARTER_MAIN", List.of("BROTH", "PASTA"));
    }

    private static BusinessLunchService.Request request(BusinessLunchOffer offer, String setCode, List<String> dishCodes) {
        return new BusinessLunchService.Request(CHAT, CHAT, offer, setCode, dishCodes, 2, TOMORROW, LocalTime.of(13, 0),
                "Наталья", null, null, "DIRECT", null);
    }

    private record AnsweringProvider(Result answer) implements ExternalLunchOrderProvider {

        @Override
        public String providerId() {
            return "SABY";
        }

        @Override
        public ExternalReservationStatus status() {
            return new ExternalReservationStatus("SABY", true, true, List.of(), "READY");
        }

        @Override
        public Result submit(BusinessLunchOrder order, String idempotencyKey) {
            return answer;
        }
    }
}
