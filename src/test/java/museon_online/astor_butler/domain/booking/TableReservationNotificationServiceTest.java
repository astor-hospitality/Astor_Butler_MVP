package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TableReservationNotificationServiceTest {

    private final TableReservationNotificationService service = new TableReservationNotificationService(null, null, null);

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
