package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.shift.ButlerPauseService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The guest writes «счёт» to the bot (#153 A2): the team gets a card, the guest an answer, and — at the bill — the
 * same review question the hostess «Счёт» button asks, once per visit whoever asked first.
 */
class GuestBillRequestServiceTest {

    private static final long CHAT = 1773317437L;
    private static final long HOSTESS_CHAT = -1001234567890L;
    /** 13:30 in Yekaterinburg (UTC+5) on 13.10.2026: the lunch is on. */
    private static final Instant NOW = Instant.parse("2026-10-13T08:30:00Z");
    private static final ZoneId VENUE = ZoneId.of("Asia/Yekaterinburg");

    private final BillingProperties billing = new BillingProperties();
    private final ReviewProperties reviewProperties = new ReviewProperties();
    private final GuestBillRequestProperties properties = new GuestBillRequestProperties();
    private final GuestBillNotifier notifier = mock(GuestBillNotifier.class);
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private GuestBillService bills;
    private VisitReviewService reviews;
    private HostessBillRequestService hostessButton;
    private GuestBillRequestService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = BillingTestDatabase.create();
        bills = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
        reviews = new VisitReviewService(billing, bills, new VisitReviewRepository(jdbc), notifier,
                new GuestBillPaymentPrompterTest.MutableClock(NOW));
        hostessButton = new HostessBillRequestService(billing, bills, reviews);
        hostessButton.setHostessChatId(Long.toString(HOSTESS_CHAT));
        service = new GuestBillRequestService(properties, reviewProperties, hostessButton, reservations, notifications, notifier,
                redis, new GuestBillPaymentPrompterTest.MutableClock(NOW));
        service.setKeyPrefix("astor");
        properties.setEnabled(true);
        billing.setEnabled(true);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(notifications.notifyHostessGuestAskedForBill(any())).thenReturn(true);
        when(notifier.billRequestReply(any(), anyString())).thenReturn(true);
        when(notifier.reviewPrompt(any())).thenReturn(true);
        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(List.of(order(77L, TableReservationStatus.CONFIRMED, NOW.minus(Duration.ofMinutes(30)))));
    }

    @Test
    void withTheFlagOffTheWordsGoToTheUsualFlow() {
        properties.setEnabled(false);

        assertThat(service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт")).isEmpty();
        verifyNoInteractions(reservations, notifications, redis);
    }

    @Test
    void onlyBillWordsOutsideADialogueStepAreTaken() {
        assertThat(service.handle(guest("счётчик"), BotState.READY_FOR_DIALOG, "счётчик")).isEmpty();
        assertThat(service.handle(guest("меню кухни"), BotState.READY_FOR_DIALOG, "меню кухни")).isEmpty();
        assertThat(service.handle(guest("счёт"), BotState.BUSINESS_LUNCH_CHOOSE_DISH, "счёт")).isEmpty();
        assertThat(service.handle(guest("счёт"), BotState.TABLE_BOOKING_COLLECT_TIME, "счёт")).isEmpty();
        assertThat(service.handle(guest("счёт"), BotState.TIP_COLLECT_AMOUNT, "счёт")).isEmpty();
        assertThat(service.handle(guest("счёт"), BotState.CONSENT_REQUIRED, "счёт")).isEmpty();
        assertThat(service.handle(guest("счёт"), BotState.UNKNOWN, "счёт")).isEmpty();
        assertThat(service.handle(guest("счёт"), null, "счёт")).isEmpty();
        assertThat(service.handle(groupChat("счёт"), BotState.READY_FOR_DIALOG, "счёт")).isEmpty();
        verifyNoInteractions(reservations, notifications, redis);

        assertThat(service.handle(guest("счёт"), BotState.AI_FALLBACK, "счёт")).isPresent();
        assertThat(service.handle(guest("счёт"), BotState.MENU, "счёт")).isPresent();
    }

    @Test
    void aGuestWithoutAVisitTodayIsToldSoAndOfferedTheMenu() {
        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(List.of(
                order(70L, TableReservationStatus.CONFIRMED, NOW.minus(Duration.ofDays(1))),
                order(71L, TableReservationStatus.CONFIRMED, NOW.plus(Duration.ofDays(1)))));

        OutgoingMessage reply = service.handle(guest("можно счёт?"), BotState.READY_FOR_DIALOG, "можно счёт?").orElseThrow();

        assertThat(reply.text()).contains("активного визита на сегодня").contains("меню");
        assertThat(reply.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(reply.actions()).containsExactly("GUEST_BILL_REQUEST", "NO_ACTIVE_VISIT");
        verifyNoInteractions(notifications, redis);
        verify(notifier, never()).billRequestReply(any(), anyString());

        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(List.of());
        assertThat(service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт").orElseThrow().actions()).contains("NO_ACTIVE_VISIT");
    }

    @Test
    void theTeamGetsTheCardAndTheGuestAnAnswerWithoutAReviewBeforeTheBillIsTheReviewMoment() {
        OutgoingMessage reply = service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт").orElseThrow();

        verify(notifications).notifyHostessGuestAskedForBill(any());
        verify(notifier).billRequestReply(CHAT, "Передал команде, счёт сейчас принесут.");
        verify(notifier, never()).reviewPrompt(any());
        verify(values).setIfAbsent(eq("astor:billing:guest-bill-request:77"), anyString(), eq(Duration.ofMinutes(10)));
        // The answer already went out ahead of the stars; the FSM reply carries only the trace.
        assertThat(reply.text()).isEmpty();
        assertThat(reply.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(reply.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOTIFIED");
        assertThat(reply.metadata()).containsEntry("reservationId", 77L);
    }

    @Test
    void atTheBillTheReviewIsAskedAfterTheAnswerExactlyAsByTheHostessButton() {
        reviewProperties.setMoment(ReviewMoment.ON_BILL);
        GuestBill bill = bill(77L);

        OutgoingMessage reply = service.handle(guest("посчитайте нас"), BotState.READY_FOR_DIALOG, "посчитайте нас").orElseThrow();

        InOrder order = inOrder(notifications, notifier);
        order.verify(notifications).notifyHostessGuestAskedForBill(any());
        order.verify(notifier).billRequestReply(CHAT, "Передал команде, счёт сейчас принесут.");
        order.verify(notifier).reviewPrompt(any());
        assertThat(reply.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOTIFIED", "REVIEW_SENT");
        assertThat(reviews.reviewOf(bill.id())).isPresent();
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::type, BillOperation::details).containsExactly(
                tuple(BillOperationType.ESTIMATE_CREATED, "estimate=54000"),
                tuple(BillOperationType.REVIEW_PROMPTED, "trigger=guest"));

        // Parity: whoever asked first, the hostess button now finds the question asked and asks nothing more.
        assertThat(hostessButton.handleCallback("table_booking:bill:77", HOSTESS_CHAT).answerText()).contains("уже задан");
        assertThat(reviews.promptDueVisits()).isZero();
        verify(notifier, times(1)).reviewPrompt(any());
    }

    @Test
    void afterTheHostessButtonTheGuestsOwnWordsStillReachTheTeamButAskNoSecondReview() {
        reviewProperties.setMoment(ReviewMoment.ON_BILL);
        GuestBill bill = bill(77L);
        hostessButton.handleCallback("table_booking:bill:77", HOSTESS_CHAT);

        OutgoingMessage reply = service.handle(guest("чек"), BotState.READY_FOR_DIALOG, "чек").orElseThrow();

        verify(notifications).notifyHostessGuestAskedForBill(any());
        verify(notifier).billRequestReply(CHAT, "Передал команде, счёт сейчас принесут.");
        verify(notifier, times(1)).reviewPrompt(any());
        assertThat(reply.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOTIFIED", "REVIEW_ALREADY_ASKED");
        assertThat(bills.historyOf(bill.id())).extracting(BillOperation::details).containsExactly("estimate=54000", "trigger=hostess");
    }

    @Test
    void withoutABillTheTeamStillGetsTheCardAndTheReviewIsSimplyNotAsked() {
        reviewProperties.setMoment(ReviewMoment.ON_BILL);

        OutgoingMessage noBill = service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт").orElseThrow();
        assertThat(noBill.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOTIFIED", "REVIEW_NO_BILL");

        billing.setEnabled(false);
        bill(77L);
        OutgoingMessage billingOff = service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт").orElseThrow();
        assertThat(billingOff.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOTIFIED", "REVIEW_BILLING_OFF");

        verify(notifications, times(2)).notifyHostessGuestAskedForBill(any());
        verify(notifier, times(2)).billRequestReply(CHAT, "Передал команде, счёт сейчас принесут.");
        verify(notifier, never()).reviewPrompt(any());
    }

    @Test
    void aSecondAskWithinTheWindowSendsTheTeamNothingAndSaysSo() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);

        service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт");
        OutgoingMessage second = service.handle(guest("счёт, пожалуйста"), BotState.READY_FOR_DIALOG, "счёт, пожалуйста").orElseThrow();

        assertThat(second.text()).isEqualTo("Уже передал — счёт сейчас принесут.");
        assertThat(second.actions()).containsExactly("GUEST_BILL_REQUEST", "ALREADY_SENT");
        verify(notifications, times(1)).notifyHostessGuestAskedForBill(any());
        verify(notifier, times(1)).billRequestReply(any(), anyString());
    }

    @Test
    void whenTheStaffChatCannotBeReachedTheGuestIsAskedToCallTheWaiterAndMayTryAgain() {
        when(notifications.notifyHostessGuestAskedForBill(any())).thenReturn(false);

        OutgoingMessage reply = service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт").orElseThrow();

        assertThat(reply.text()).contains("позовите официанта");
        assertThat(reply.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOT_REACHED");
        verify(redis).delete("astor:billing:guest-bill-request:77");
        verify(notifier, never()).billRequestReply(any(), anyString());
    }

    @Test
    void whenTelegramDidNotTakeTheAnswerTheFsmReplyCarriesIt() {
        when(notifier.billRequestReply(any(), anyString())).thenReturn(false);

        OutgoingMessage reply = service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт").orElseThrow();

        assertThat(reply.text()).isEqualTo("Передал команде, счёт сейчас принесут.");
        assertThat(reply.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOTIFIED");
    }

    @Test
    void aRedisHiccupCountsAsTheFirstAsk() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("redis down"));

        OutgoingMessage reply = service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт").orElseThrow();

        assertThat(reply.actions()).containsExactly("GUEST_BILL_REQUEST", "STAFF_NOTIFIED");
        verify(notifications).notifyHostessGuestAskedForBill(any());
    }

    @Test
    void whileButlerIsPausedTheWordsGoToTheUsualFlow() {
        ButlerPauseService pause = mock(ButlerPauseService.class);
        when(pause.paused()).thenReturn(true);
        service.setButlerPause(pause);

        assertThat(service.handle(guest("счёт"), BotState.READY_FOR_DIALOG, "счёт")).isEmpty();
        verifyNoInteractions(reservations, notifications, redis);
    }

    @Test
    void theVisitTodayIsTheConfirmedOneOrElseTheNearestWaitingOne() {
        TableReservationOrder waitingNear = order(80L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, NOW.plus(Duration.ofMinutes(10)));
        TableReservationOrder confirmedLater = order(81L, TableReservationStatus.CONFIRMED, NOW.plus(Duration.ofHours(5)));
        TableReservationOrder waitingFar = order(82L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, NOW.plus(Duration.ofHours(8)));
        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(List.of(waitingFar, waitingNear, confirmedLater));
        assertThat(service.visitToday(CHAT)).map(TableReservationOrder::id).contains(81L);

        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(List.of(waitingFar, waitingNear));
        assertThat(service.visitToday(CHAT)).map(TableReservationOrder::id).contains(80L);

        // 23:30 at the venue is still today; the next calendar day at the venue is not.
        Instant lateTonight = NOW.atZone(VENUE).toLocalDate().atTime(23, 30).atZone(VENUE).toInstant();
        Instant tomorrowMorning = NOW.atZone(VENUE).toLocalDate().plusDays(1).atTime(0, 30).atZone(VENUE).toInstant();
        when(reservations.findActiveOrdersByChatId(CHAT)).thenReturn(List.of(
                order(83L, TableReservationStatus.CONFIRMED, tomorrowMorning), order(84L, TableReservationStatus.CONFIRMED, lateTonight)));
        assertThat(service.visitToday(CHAT)).map(TableReservationOrder::id).contains(84L);
        assertThat(service.visitToday(CHAT)).isEqualTo(Optional.of(order(84L, TableReservationStatus.CONFIRMED, lateTonight)));
    }

    @Test
    void byReservationTheTeamIsToldOnceAndTheBillComesBack() {
        when(reservations.findOrder(77L)).thenReturn(Optional.of(order(77L, TableReservationStatus.SEATED, NOW.minus(Duration.ofMinutes(30)))));
        GuestBill bill = bill(77L);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true, false);

        BillRequestOutcome first = service.requestByReservation(77L, "GUEST");
        BillRequestOutcome second = service.requestByReservation(77L, "GUEST");

        assertThat(first.status()).isEqualTo(BillRequestOutcome.Status.STAFF_NOTIFIED);
        assertThat(first.billId()).isEqualTo(bill.id());
        assertThat(first.totalRub()).as("Butler's estimate (540 ₽) is not the bill").isNull();
        assertThat(first.paymentUrl()).isNull();
        assertThat(second.status()).isEqualTo(BillRequestOutcome.Status.ALREADY_SENT);
        verify(notifications, times(1)).notifyHostessGuestAskedForBill(any());
        verify(notifier, never()).billRequestReply(any(), anyString());
    }

    /** 16.10: the guest was told Butler's 2230 ₽ while Saby had 2510 ₽; only the venue's sum is told. */
    @Test
    void byReservationTheSumIsTheVenuesWhenItReportedOne() {
        when(reservations.findOrder(77L)).thenReturn(Optional.of(order(77L, TableReservationStatus.SEATED, NOW.minus(Duration.ofMinutes(30)))));
        GuestBill bill = bill(77L);
        bills.issued(bill.id(), "SABY", "order-77");
        bills.venueReported("SABY", "order-77", BillPayState.UNPAID, 251000L);

        BillRequestOutcome outcome = service.requestByReservation(77L, "GUEST");

        assertThat(outcome.totalRub()).isEqualTo(2510L);
    }

    @Test
    void anAgentsRequestPostsTheBillCardWithTheWaiterAndDeliversThePaymentLink() {
        GuestBillStaffCard card = mock(GuestBillStaffCard.class);
        when(card.billAsked(any(), any())).thenReturn(true);
        service.setStaffCard(card);
        ExternalPaymentProvider saby = mock(ExternalPaymentProvider.class);
        when(saby.enabled()).thenReturn(true);
        when(saby.providerId()).thenReturn("SABY");
        when(saby.paymentLink("order-77")).thenReturn(Optional.of(new ExternalPaymentProvider.PaymentLink("https://pay.test/77", null)));
        service.setPaymentProviders(List.of(saby));
        when(notifier.paymentLinkQueued(any(), eq("https://pay.test/77"))).thenReturn(true);
        when(reservations.findOrder(77L)).thenReturn(Optional.of(order(77L, TableReservationStatus.SEATED, NOW.minus(Duration.ofMinutes(30)))));
        GuestBill bill = bill(77L);
        bills.issued(bill.id(), "SABY", "order-77");

        BillRequestOutcome outcome = service.requestByReservation(77L, "GUEST");

        assertThat(outcome.status()).isEqualTo(BillRequestOutcome.Status.STAFF_NOTIFIED);
        assertThat(outcome.paymentUrl()).isEqualTo("https://pay.test/77");
        assertThat(outcome.paymentLinkSent()).isTrue();
        assertThat(outcome.totalRub()).isNull();
        verify(card).billAsked(argThat(order -> order.id() == 77L), argThat(found -> found.id().equals(bill.id())));
        verify(notifications, never()).notifyHostessGuestAskedForBill(any());
        assertThat(bills.find(bill.id()).orElseThrow().paymentLinkSentAt()).as("a bot guest's link is marked sent").isNotNull();
    }

    @Test
    void withTheStaffChatUnreachableTheLinkIsNotHandedOut() {
        GuestBillStaffCard card = mock(GuestBillStaffCard.class);
        when(card.billAsked(any(), any())).thenReturn(false);
        service.setStaffCard(card);
        ExternalPaymentProvider saby = mock(ExternalPaymentProvider.class);
        when(saby.enabled()).thenReturn(true);
        when(saby.providerId()).thenReturn("SABY");
        service.setPaymentProviders(List.of(saby));
        when(reservations.findOrder(77L)).thenReturn(Optional.of(order(77L, TableReservationStatus.SEATED, NOW.minus(Duration.ofMinutes(30)))));
        bills.issued(bill(77L).id(), "SABY", "order-77");

        BillRequestOutcome outcome = service.requestByReservation(77L, "GUEST");

        assertThat(outcome.status()).isEqualTo(BillRequestOutcome.Status.STAFF_NOT_REACHED);
        assertThat(outcome.paymentUrl()).isNull();
        verify(saby, never()).paymentLink(anyString());
        verify(notifier, never()).paymentLinkQueued(any(), anyString());
    }

    @Test
    void byReservationOnlyAVisitOfToday() {
        when(reservations.findOrder(78L)).thenReturn(Optional.of(order(78L, TableReservationStatus.CONFIRMED, NOW.plus(Duration.ofDays(2)))));
        when(reservations.findOrder(79L)).thenReturn(Optional.empty());

        assertThat(service.requestByReservation(78L, "GUEST").status()).isEqualTo(BillRequestOutcome.Status.NO_VISIT);
        assertThat(service.requestByReservation(79L, "GUEST").status()).isEqualTo(BillRequestOutcome.Status.NO_VISIT);
        properties.setEnabled(false);
        assertThat(service.requestByReservation(78L, "GUEST").status()).isEqualTo(BillRequestOutcome.Status.OFF);
        verify(notifications, never()).notifyHostessGuestAskedForBill(any());
    }

    private GuestBill bill(long reservationId) {
        return bills.open(new BillDraft(CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", reservationId, "test",
                OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ", 2, 270))), NOW.plus(Duration.ofHours(1))));
    }

    private static IncomingMessage guest(String text) {
        return IncomingMessage.telegram(CHAT, CHAT, 1, 1, text, null, "Наталья", null, "natalia", "ru", false, "corr-" + text);
    }

    private static IncomingMessage groupChat(String text) {
        return IncomingMessage.telegram(HOSTESS_CHAT, CHAT, 1, 1, text, null, "Наталья", null, "natalia", "ru", false, "corr-group");
    }

    private static TableReservationOrder order(long id, TableReservationStatus status, Instant startAt) {
        return new TableReservationOrder(
                id,
                CHAT,
                CHAT,
                null,
                5L,
                "5",
                "Table 5",
                null,
                "Бизнес-ланч",
                status,
                "TELEGRAM",
                startAt,
                startAt.plus(Duration.ofMinutes(90)),
                2,
                "Наталья",
                "+79990000000",
                null,
                null,
                null,
                Long.toString(HOSTESS_CHAT),
                null,
                NOW.minus(Duration.ofHours(3)),
                NOW.minus(Duration.ofHours(3))
        );
    }
}
