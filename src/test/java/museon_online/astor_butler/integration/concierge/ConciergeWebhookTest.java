package museon_online.astor_butler.integration.concierge;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.TurnChannel;
import museon_online.astor_butler.domain.ledger.TurnKind;
import museon_online.astor_butler.domain.ledger.TurnSide;
import museon_online.astor_butler.domain.ledger.VisitTurnDraft;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConciergeWebhookTest {

    private static final String SECRET = "test-webhook-secret-0123";
    private static final String URL = "http://concierge.test:8096/api/concierge/v1/events";
    private static final Instant AT = Instant.parse("2026-10-14T14:00:00Z");

    private final ConciergeWebhookProperties properties = new ConciergeWebhookProperties();
    private final LedgerWriter ledger = mock(LedgerWriter.class);
    private final VisitTurnRepository turns = mock(VisitTurnRepository.class);
    private final GuestVisitRepository visits = mock(GuestVisitRepository.class);
    private final List<Long> sleeps = new ArrayList<>();
    private MockRestServiceServer server;
    private ConciergeWebhook webhook;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setUrl("http://concierge.test:8096/");
        properties.setSecret(SECRET);
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        webhook = new ConciergeWebhook(properties, rest, new ObjectMapper(), ledger, turns, visits, Runnable::run, sleeps::add);
    }

    @Test
    void signsTheBodyWithHmac() {
        byte[] body = "{\"type\":\"CONFIRMED\",\"reservationId\":44}".getBytes(StandardCharsets.UTF_8);

        assertThat(ConciergeWebhook.sign(SECRET, body))
                .isEqualTo("8c5326b5d3dc20eff3b6c43827b064e8f7564da9bc46a17bc77feb949a81b42d");
    }

    @Test
    void postsTheEventSignedOverTheExactBytesSent() {
        server.expect(once(), requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.type").value("CONFIRMED"))
                .andExpect(jsonPath("$.reservationId").value(44))
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.butlerText").value("Стол подтверждён: Table 5 на 19:00. Ждём вас."))
                .andExpect(request -> {
                    byte[] sent = ((MockClientHttpRequest) request).getBodyAsBytes();
                    assertThat(request.getHeaders().getFirst("X-Astor-Signature"))
                            .isEqualTo("sha256=" + ConciergeWebhook.sign(SECRET, sent))
                            .matches("sha256=[0-9a-f]{64}");
                })
                .andRespond(withSuccess());

        boolean delivered = webhook.deliver(event("CONFIRMED", Map.of("table", "Table 5", "time", "19:00")));

        assertThat(delivered).isTrue();
        server.verify();
        VisitTurnDraft row = ledgerRow();
        assertThat(row.side()).isEqualTo(TurnSide.BUTLER);
        assertThat(row.channel()).isEqualTo(TurnChannel.INTERNAL);
        assertThat(row.kind()).isEqualTo(TurnKind.MESSAGE);
        assertThat(row.visitId()).isEqualTo(7L);
        assertThat(row.sessionId()).isEqualTo("s-1");
        assertThat(row.payload()).containsEntry("type", "CONFIRMED").containsEntry("reservationId", 44L)
                .containsEntry("attempts", 1).containsEntry("status", "200");
    }

    @Test
    void retriesThreeTimesThenGivesUp() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        boolean delivered = webhook.deliver(event("CONFIRMED", Map.of()));

        assertThat(delivered).isFalse();
        server.verify();
        assertThat(sleeps).containsExactly(1000L, 4000L);
        assertThat(ledgerRow().payload()).containsEntry("attempts", 3).containsEntry("status", "502");
    }

    @Test
    void aRefusalIsNotRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(webhook.deliver(event("CONFIRMED", Map.of()))).isFalse();

        server.verify();
        assertThat(sleeps).isEmpty();
        assertThat(ledgerRow().payload()).containsEntry("attempts", 1).containsEntry("status", "401");
    }

    @Test
    void aShortSecretSendsNothing() {
        properties.setSecret("short");

        assertThat(webhook.ready()).isFalse();
        assertThat(webhook.send(event("CONFIRMED", Map.of()))).isFalse();
        server.verify();
    }

    @Test
    void onlyASessionBookingIsAnnounced() {
        when(visits.findByReservation(44L)).thenReturn(Optional.of(visit()));
        when(turns.sessionOf(7L)).thenReturn(Optional.empty());

        assertThat(webhook.announce("CONFIRMED", concierge(AT))).isFalse();
        server.verify();
    }

    @Test
    void movedIsSentOncePerNewTime() {
        when(visits.findByReservation(44L)).thenReturn(Optional.of(visit()));
        when(turns.sessionOf(7L)).thenReturn(Optional.of("s-1"));
        server.expect(times(2), requestTo(URL)).andExpect(jsonPath("$.type").value("MOVED")).andRespond(withSuccess());

        assertThat(webhook.announce("MOVED", concierge(AT))).isTrue();
        assertThat(webhook.announce("MOVED", concierge(AT))).isFalse();
        assertThat(webhook.announce("MOVED", concierge(AT.plusSeconds(3600)))).isTrue();

        server.verify();
    }

    @Test
    void billReadyCarriesTheTotalTheLinkAndTheBill() {
        when(visits.findByReservation(44L)).thenReturn(Optional.of(visit()));
        when(turns.sessionOf(7L)).thenReturn(Optional.of("s-1"));
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.type").value("BILL_READY"))
                .andExpect(jsonPath("$.facts.totalRub").value(2450.5))
                .andExpect(jsonPath("$.facts.paymentUrl").value("https://pay.test/1"))
                .andExpect(jsonPath("$.facts.billId").value(9))
                .andExpect(jsonPath("$.butlerText").value("Счёт 2450.5 ₽ — оплатить: https://pay.test/1"))
                .andRespond(withSuccess());

        assertThat(webhook.announce("BILL_READY", 44L, null,
                Map.of("totalRub", new BigDecimal("2450.5"), "paymentUrl", "https://pay.test/1", "billId", 9L))).isTrue();

        server.verify();
    }

    private VisitTurnDraft ledgerRow() {
        ArgumentCaptor<VisitTurnDraft> row = ArgumentCaptor.forClass(VisitTurnDraft.class);
        verify(ledger).write(row.capture());
        return row.getValue();
    }

    private static ConciergeEvent event(String type, Map<String, Object> facts) {
        return ConciergeEvent.of(type, 44L, 7L, "s-1", facts);
    }

    private static GuestVisit visit() {
        return new GuestVisit(7L, 44L, null, null, "AERIS", JourneyStage.REQUESTED, null, null, 0, AT, AT, null);
    }

    private static TableReservationOrder concierge(Instant startAt) {
        return new TableReservationOrder(44L, null, null, null, 5L, "5", "Table 5", null, null,
                TableReservationStatus.CONFIRMED, "CONCIERGE", startAt, startAt.plusSeconds(7200), 2, "Мария", null, null,
                null, null, null, null, AT, AT);
    }
}
