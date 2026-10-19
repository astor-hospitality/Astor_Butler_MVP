package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.calllog.CallLogWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestBillCallLogTest {

    private static final long CHAT = 1773317437L;

    private final JdbcTemplate jdbc = BillingTestDatabase.create();
    private final GuestBillService service = new GuestBillService(new GuestBillRepository(jdbc), new BillOperationRepository(jdbc));
    private final CallLogWriter callLog = mock(CallLogWriter.class);

    @Test
    void openingABillIsATargetAction() {
        service.setCallLog(callLog);
        when(callLog.enabled()).thenReturn(true);

        service.open(draft());

        verify(callLog).targetAction("bill:open", "ESTIMATED", "DIRECT", String.valueOf(CHAT));
    }

    @Test
    void offTheWriterIsNotCalled() {
        service.setCallLog(callLog);

        service.open(draft());

        verify(callLog, never()).targetAction(any(), any(), any(), any());
    }

    private static BillDraft draft() {
        return new BillDraft(CHAT, CHAT, "aeris", GuestBillKind.BUSINESS_LUNCH, "direct", 77L, "business-lunch:AERIS",
                OrderEstimate.of(List.of(OrderEstimate.Line.priced("BORSCHT", "Борщ", 1, 270))),
                java.time.Instant.parse("2026-10-06T09:30:00Z"));
    }
}
