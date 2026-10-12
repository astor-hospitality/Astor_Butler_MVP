package museon_online.astor_butler.domain.lunch;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** #164: the kitchen's «готовить ко времени» — venue time, the lead taken off, and nothing at all while the flag is off. */
class LunchPreorderMarkTest {

    // 13:00 in Yekaterinburg (UTC+5).
    private static final Instant LUNCH_13_00 = Instant.parse("2026-10-07T08:00:00Z");

    @Test
    void isSilentWhileTheFlagIsOff() {
        LunchPreorderMark off = new LunchPreorderMark(false, 15);

        assertThat(off.isEnabled()).isFalse();
        assertThat(off.readyAt(LUNCH_13_00)).isEmpty();
        assertThat(off.sabyLine(LUNCH_13_00, 2)).isEmpty();
        assertThat(off.cardLine(lunch(LUNCH_13_00))).isEmpty();
    }

    @Test
    void namesTheLunchStartInVenueTimeWithTheGuests() {
        LunchPreorderMark on = new LunchPreorderMark(true, 0);

        assertThat(on.readyAt(LUNCH_13_00)).contains("13:00");
        assertThat(on.sabyLine(LUNCH_13_00, 2)).contains("ПРЕДЗАКАЗ: готовить ко времени 13:00, гостей 2");
        assertThat(on.cardLine(lunch(LUNCH_13_00))).contains("⏱ Готовить ко времени 13:00");
        // Half past midnight UTC is already the next morning in Yekaterinburg: the hour is the venue's, not the server's.
        assertThat(on.readyAt(Instant.parse("2026-10-07T19:30:00Z"))).contains("00:30");
        assertThat(on.readyAt(null)).isEmpty();
    }

    @Test
    void takesTheLeadOffTheStartAndNeverAddsToIt() {
        assertThat(new LunchPreorderMark(true, 10).sabyLine(LUNCH_13_00, 4)).contains("ПРЕДЗАКАЗ: готовить ко времени 12:50, гостей 4");
        assertThat(new LunchPreorderMark(true, 75).readyAt(LUNCH_13_00)).contains("11:45");
        assertThat(new LunchPreorderMark(true, -5).leadMinutes()).isZero();
        assertThat(new LunchPreorderMark(true, -5).readyAt(LUNCH_13_00)).contains("13:00");
    }

    @Test
    void theCardLineIsForLunchesOnly() {
        LunchPreorderMark on = new LunchPreorderMark(true, 0);
        TableReservationOrder table = lunch(LUNCH_13_00);
        TableReservationOrder plain = new TableReservationOrder(table.id(), table.chatId(), table.telegramUserId(), null, table.tableId(),
                table.tableCode(), table.tableDisplayName(), null, "у окна", table.status(), table.source(), table.requestedStartAt(),
                table.requestedEndAt(), table.partySize(), table.guestName(), table.guestPhone(), table.guestComment(), null, null, null, null,
                table.createdAt(), table.updatedAt());

        assertThat(on.cardLine(plain)).isEmpty();
        assertThat(on.cardLine(null)).isEmpty();
        assertThat(LunchPreorderMark.isLunch(table)).isTrue();
        assertThat(LunchPreorderMark.isLunch(plain)).isFalse();
    }

    @Test
    void aMovedLunchGetsTheNewTimeInFrontAndAnyOtherCommentIsLeftAlone() {
        LunchPreorderMark on = new LunchPreorderMark(true, 10);
        String moved = on.refresh("ПРЕДЗАКАЗ: готовить ко времени 12:50, гостей 2 · Бизнес-ланч: Борщ × 2. · Astor Butler #77",
                Instant.parse("2026-10-07T09:30:00Z"), 3);

        assertThat(moved).isEqualTo("ПРЕДЗАКАЗ: готовить ко времени 14:20, гостей 3 · Бизнес-ланч: Борщ × 2. · Astor Butler #77");
        assertThat(on.refresh("ПРЕДЗАКАЗ: готовить ко времени 12:50, гостей 2", LUNCH_13_00, 2)).isEqualTo("ПРЕДЗАКАЗ: готовить ко времени 12:50, гостей 2");
        assertThat(on.refresh("Hostess note · Astor Butler #12", LUNCH_13_00, 2)).isEqualTo("Hostess note · Astor Butler #12");
        assertThat(on.refresh(null, LUNCH_13_00, 2)).isNull();
        assertThat(on.refresh("ПРЕДЗАКАЗ: готовить ко времени 12:50, гостей 2 · x", null, 2)).isEqualTo("ПРЕДЗАКАЗ: готовить ко времени 12:50, гостей 2 · x");
        // The flag off now does not leave a stale time behind: the line is there only because the mark was on when the dishes went.
        assertThat(new LunchPreorderMark(false, 0).refresh("ПРЕДЗАКАЗ: готовить ко времени 12:50, гостей 2 · Astor Butler #77", LUNCH_13_00, 2))
                .isEqualTo("ПРЕДЗАКАЗ: готовить ко времени 13:00, гостей 2 · Astor Butler #77");
    }

    private static TableReservationOrder lunch(Instant startAt) {
        return new TableReservationOrder(77L, 1773317437L, 1773317437L, null, 4L, "4", "Стол 4", null, BusinessLunchService.SEATING_LABEL,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM", startAt, startAt.plusSeconds(5400), 2, "Наталья", "+79990000000",
                "Бизнес-ланч: Борщ × 2.", 876857557L, null, null, null, startAt.minusSeconds(86400), startAt.minusSeconds(86400));
    }
}
