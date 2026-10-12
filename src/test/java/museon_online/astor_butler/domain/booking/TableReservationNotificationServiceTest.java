package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.billing.HostessBillRequestService;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static museon_online.astor_butler.domain.booking.BookingAutoConfirmPolicy.Decision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableReservationNotificationServiceTest {

    private final BookingAutoConfirmPolicy policy = mock(BookingAutoConfirmPolicy.class);
    private final TableReservationNotificationService service =
            new TableReservationNotificationService(null, null, null, policy, null);

    @Test
    void hostessCardIsUnchangedWhileTheRestaurantSystemIsOff() {
        assertThat(service.externalSyncLine(order(), null)).isEmpty();
        assertThat(service.externalSyncLine(order(), ExternalReservationResult.rejectedBecauseUnconfigured("SABY", List.of())))
                .isEmpty();
    }

    @Test
    void hostessCardSaysTheBookingIsAlreadyInSabyAndStillNeedsATable() {
        assertThat(service.externalSyncLine(order(), result(true, "SABY_ORDER_CREATED_UNCONFIRMED", "saby-1")))
                .contains("бронь уже создана в Presto без стола", "снимется в Saby автоматически");
    }

    @Test
    void hostessCardSaysWhatToDoByHandWhenTheBookingIsNotInSaby() {
        assertThat(service.externalSyncLine(order(), result(false, "SABY_WRITE_DISABLED", "")))
                .contains("запись из Butler выключена", "вручную");
        assertThat(service.externalSyncLine(order(), result(false, "GUEST_DATA_REQUIRED", "")))
                .contains("нет имени или телефона гостя", "вручную");
        assertThat(service.externalSyncLine(order(), result(false, "PROVIDER_REJECTED", "")))
                .contains("бронь не записалась в Presto", "вручную");
    }

    @Test
    void hostessCardSaysTheChangeIsAlreadyInSaby() {
        assertThat(service.externalSyncLine(order(), result(true, TableReservationService.EXTERNAL_CHANGE_SYNCED, "saby-1")))
                .contains("изменение уже в Presto")
                .doesNotContain("без стола");
    }

    @Test
    void hostessCardWarnsAgainstADuplicateWhenSabyDidNotAnswer() {
        assertThat(service.externalSyncLine(order(), result(false, "PROVIDER_RESULT_UNKNOWN", "")))
                .contains("Astor Butler #44", "только если её там нет");
    }

    @Test
    void hostessCardSaysAChangeDidNotReachSaby() {
        assertThat(service.externalSyncLine(
                order(), result(false, TableReservationService.EXTERNAL_CHANGE_NOT_SYNCED, "saby-1")))
                .contains("изменение не попало в Presto");
    }

    @Test
    void hostessCardIsUnchangedWhileAutoConfirmIsOffOrPrestoDecides() {
        assertThat(service.modeLine(order(), Decision.OFF)).isEmpty();
        assertThat(service.modeLine(order(), Decision.VENUE_SYSTEM)).isEmpty();
        assertThat(service.modeLine(order(), null)).isEmpty();
    }

    @Test
    void hostessCardSaysTheRequestConfirmsItselfAfterTheTimeout() {
        when(policy.timeoutMinutes()).thenReturn(7);

        assertThat(service.modeLine(order(), Decision.AUTO))
                .contains("через 7 мин", "подтвердится");
    }

    @Test
    void hostessCardSaysManualModeSendsTheCardToTheManager() {
        when(policy.timeoutMinutes()).thenReturn(7);

        assertThat(service.modeLine(order(), Decision.MANUAL_MODE))
                .contains("Ручной режим", "7 мин", "менеджеру", "/manual off");
    }

    @Test
    void hostessCardSaysALargePartyOrAnExcludedZoneNeedsADecision() {
        when(policy.timeoutMinutes()).thenReturn(7);

        assertThat(service.modeLine(order(), Decision.LARGE_PARTY))
                .contains("Требует решения: 2 гостей", "менеджеру");
        assertThat(service.modeLine(order(), Decision.EXCLUDED_ZONE))
                .contains("Требует решения", "зона", "менеджеру");
    }

    @Test
    void managerCardSaysWhyItCameAndKeepsTheRequest() {
        assertThat(service.managerEscalationText(order(), Decision.MANUAL_MODE, 7))
                .contains("7 минут без ответа", "ручной режим", "Заказ:</b> #44", "Наталья");
        assertThat(service.managerEscalationText(order(), Decision.LARGE_PARTY, 7))
                .contains("7 минут без ответа", "2 гостей");
    }

    @Test
    void hostessIsToldTheCardWentToTheManagerOrConfirmedItself() {
        assertThat(service.hostessEscalatedText(order(), Decision.MANUAL_MODE, 7))
                .contains("7 минут без ответа", "#44", "менеджеру");
        assertThat(service.hostessAutoConfirmedText(order(), 7))
                .contains("Подтверждено автоматически через 7 мин", "#44");
    }

    @Test
    void guestHearsTheRequestIsWithTheManager() {
        assertThat(service.guestEscalatedText(order()))
                .contains("у менеджера", "ответим в ближайшее время", "#44");
    }

    @Test
    void guestHearsTheTeamDidNotMakeItInTimeAndMayPickAnotherTime() {
        assertThat(service.guestHoldExpiredText(order(), 15))
                .contains("Команда не успела подтвердить бронь за 15 минут",
                        "Передаю менеджеру смены", "выберите другое время", "#44", "Бронь стола");
    }

    @Test
    void hostessCardIsClosedWithExpiredWithoutThePhone() {
        String text = service.hostessHoldExpiredText(order(), 15);

        assertThat(text).contains("⏱ Истекло", "15 мин", "#44", "менеджеру");
        assertThat(text).doesNotContain("+7999");
    }

    @Test
    void managerGetsTheExpiredCardWithoutThePhone() {
        String text = service.managerHoldExpiredText(order(), 15);

        assertThat(text).contains("15 мин без ответа", "удержание истекло", "Заказ:</b> #44", "Наталья", "«Да»", "«Нет»");
        assertThat(text).doesNotContain("+7999", "Телефон", "Telegram:");
    }

    @Test
    void hostessCardHasOnlyYesAndNoUnlessTheReviewIsAskedAtTheBill() {
        InlineKeyboardMarkup keyboard = service.approvalKeyboard(44L);

        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(keyboard.getKeyboard().getFirst()).extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(tuple("Да", "table_booking:confirm:44"), tuple("Нет", "table_booking:reject:44"));
        assertThat(service.billKeyboard(44L)).isNull();

        service.setReviewMoment("something-unknown");
        assertThat(service.approvalKeyboard(44L).getKeyboard()).hasSize(1);
        assertThat(service.billKeyboard(44L)).isNull();
    }

    @Test
    void hostessCardOffersTheBillButtonWhenTheReviewIsAskedAtTheBill() {
        service.setReviewMoment("on-bill");

        InlineKeyboardMarkup keyboard = service.approvalKeyboard(44L);

        assertThat(keyboard.getKeyboard()).hasSize(2);
        assertThat(keyboard.getKeyboard().getFirst()).extracting(InlineKeyboardButton::getText).containsExactly("Да", "Нет");
        assertThat(keyboard.getKeyboard().get(1)).extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(tuple("Счёт", HostessBillRequestService.CALLBACK_PREFIX + "44"));
        assertThat(service.billKeyboard(44L).getKeyboard()).hasSize(1);
        assertThat(service.billKeyboard(44L).getKeyboard().getFirst()).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("table_booking:bill:44");
    }

    @Test
    void managerCardHasOnlyYesAndNoEvenWhenTheReviewIsAskedAtTheBill() {
        service.setReviewMoment("on-bill");

        InlineKeyboardMarkup keyboard = service.managerDecisionKeyboard(44L);

        assertThat(keyboard.getKeyboard()).hasSize(1);
        assertThat(keyboard.getKeyboard().getFirst()).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("table_booking:confirm:44", "table_booking:reject:44");
    }

    @Test
    void hostessCardOffersEditOnlyWhenTheStaffMayEditBookings() {
        assertThat(service.approvalKeyboard(44L).getKeyboard()).hasSize(1);
        assertThat(service.billKeyboard(44L)).isNull();

        service.setStaffEditEnabled(true);

        InlineKeyboardMarkup keyboard = service.approvalKeyboard(44L);
        assertThat(keyboard.getKeyboard()).hasSize(2);
        assertThat(keyboard.getKeyboard().getFirst()).extracting(InlineKeyboardButton::getText).containsExactly("Да", "Нет");
        assertThat(keyboard.getKeyboard().get(1)).extracting(InlineKeyboardButton::getText, InlineKeyboardButton::getCallbackData)
                .containsExactly(tuple("✏️ Изменить", HostessBookingEditService.CALLBACK_PREFIX + "44"));
        assertThat(service.billKeyboard(44L).getKeyboard()).hasSize(1);
        assertThat(service.billKeyboard(44L).getKeyboard().getFirst()).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("table_booking:edit:44");

        service.setReviewMoment("on-bill");
        assertThat(service.approvalKeyboard(44L).getKeyboard()).extracting(row -> row.getFirst().getText())
                .containsExactly("Да", "Счёт", "✏️ Изменить");
        assertThat(service.billKeyboard(44L).getKeyboard()).extracting(row -> row.getFirst().getCallbackData())
                .containsExactly("table_booking:bill:44", "table_booking:edit:44");
        assertThat(service.managerDecisionKeyboard(44L).getKeyboard()).hasSize(1);
    }

    @Test
    void guestIsToldTheNewTimeAndWhetherTheTableStayed() {
        TableReservationOrder before = order();
        TableReservationOrder moved = new TableReservationOrder(44L, before.chatId(), before.telegramUserId(), null, 5L, "5", "Table 5",
                null, null, TableReservationStatus.CONFIRMED, "TELEGRAM", Instant.parse("2026-10-07T09:30:00Z"),
                Instant.parse("2026-10-07T11:30:00Z"), 2, "Наталья", "+79990000000", null, 876857557L, null, null, null,
                before.createdAt(), before.updatedAt());

        assertThat(service.guestMovedByStaffText(moved, before))
                .contains("Бронь #44 перенесена", "Хостес перенесла вашу бронь на 07.10.2026, 14:30 - 16:30", "стол тот же — Стол 5 (5)", "гостей: 2");

        TableReservationOrder reseated = new TableReservationOrder(44L, before.chatId(), before.telegramUserId(), null, 7L, "7", "Table 7",
                null, null, TableReservationStatus.CONFIRMED, "TELEGRAM", moved.requestedStartAt(), moved.requestedEndAt(), 2,
                "Наталья", "+79990000000", null, 876857557L, null, null, null, before.createdAt(), before.updatedAt());
        assertThat(service.guestMovedByStaffText(reseated, before)).contains("стол теперь Стол 7 (7)");
    }

    private ExternalReservationResult result(boolean created, String status, String externalId) {
        return new ExternalReservationResult(created, true, "SABY", status, externalId, "", List.of(), Map.of());
    }

    private TableReservationOrder order() {
        return new TableReservationOrder(
                44L,
                1773317437L,
                1773317437L,
                null,
                5L,
                "5",
                "Table 5",
                null,
                null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                "TELEGRAM",
                Instant.parse("2026-10-07T08:00:00Z"),
                Instant.parse("2026-10-07T10:00:00Z"),
                2,
                "Наталья",
                "+79990000000",
                null,
                876857557L,
                null,
                null,
                null,
                Instant.parse("2026-10-06T00:00:00Z"),
                Instant.parse("2026-10-06T00:00:00Z")
        );
    }
}
