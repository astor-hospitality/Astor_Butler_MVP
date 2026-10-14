package museon_online.astor_butler.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.capability.tools.CapabilityFixtures;
import museon_online.astor_butler.capability.tools.ReservationCreateToolTest;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyStoreTest {

    private Connection schema;
    private IdempotencyStore store;
    private LedgerWriter ledger;
    private CapabilityProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:capability-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        schema = dataSource.getConnection();
        ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-capability-calls.sql"));
        store = new IdempotencyStore(new JdbcTemplate(dataSource), new ObjectMapper());
        ledger = mock(LedgerWriter.class);
        properties = new CapabilityProperties();
        properties.setEnabled(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection connection = schema; var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    @Test
    void sameKeyReturnsTheSavedResultWithoutCallingTheService() {
        TableReservationService reservations = mock(TableReservationService.class);
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(reservations)), ledger,
                properties, store);

        CapabilityResult first = registry.invoke("reservation_create", CapabilityFixtures.concierge("k1"), ReservationCreateToolTest.args());
        CapabilityResult second = registry.invoke("reservation_create", CapabilityFixtures.concierge("k1"), ReservationCreateToolTest.args());

        verify(reservations, times(1)).createReservation(any());
        assertThat(first.ok()).isTrue();
        assertThat(first.facts()).containsEntry("status", "AWAITING_HOSTESS");
        assertThat(second).isEqualTo(first);
        ArgumentCaptor<VisitTurnDraft> turns = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger, times(4)).write(turns.capture());
        assertThat(turns.getAllValues().get(0).payload()).doesNotContainKey("replayed");
        assertThat(turns.getAllValues().get(2).payload()).containsEntry("replayed", true);
        assertThat(turns.getAllValues().get(3).payload()).containsEntry("replayed", true);
    }

    @Test
    void aFailureIsAFactAndIsReplayedToo() {
        CapabilityResult failed = new CapabilityResult(false, Map.of("reason", "table_busy"), CapabilityResult.NOT_ALLOWED);

        store.save("k2", "reservation_create", failed);

        assertThat(store.find("k2")).contains(failed);
        assertThat(store.find("k3")).isEmpty();
    }

    @Test
    void aSecondSaveOfTheSameKeyIsADuplicate() {
        store.save("k4", "reservation_create", CapabilityResult.ok(Map.of("reservationId", 41)));

        assertThatThrownBy(() -> store.save("k4", "reservation_create", CapabilityResult.ok(Map.of("reservationId", 42))))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(store.find("k4").orElseThrow().facts()).containsEntry("reservationId", 41);
    }

    @Test
    void aConcurrentDuplicateGetsTheStoredResult() {
        IdempotencyStore racing = mock(IdempotencyStore.class);
        CapabilityResult winner = CapabilityResult.ok(new LinkedHashMap<>(Map.of("reservationId", 40)));
        when(racing.find("k5")).thenReturn(Optional.empty(), Optional.of(winner));
        doThrow(new DuplicateKeyException("capability_calls_pkey")).when(racing).save(eq("k5"), anyString(), any());
        TableReservationService reservations = mock(TableReservationService.class);
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(reservations)), ledger,
                properties, racing);

        CapabilityResult result = registry.invoke("reservation_create", CapabilityFixtures.concierge("k5"), ReservationCreateToolTest.args());

        assertThat(result).isEqualTo(winner);
    }

    @Test
    void aKeyLongerThanTheColumnIsABadArgument() {
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(mock(TableReservationService.class))),
                ledger, properties, store);

        CapabilityResult result = registry.invoke("reservation_create", CapabilityFixtures.concierge("k".repeat(81)),
                ReservationCreateToolTest.args());

        assertThat(result.error()).isEqualTo(CapabilityResult.BAD_ARGUMENT);
    }
}
