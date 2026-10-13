package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.journey.GuestVisit;
import museon_online.astor_butler.domain.journey.GuestVisitEvent;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.journey.JourneyAxis;
import museon_online.astor_butler.domain.journey.JourneyStage;
import museon_online.astor_butler.domain.journey.VisitEventKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SabyCrmMirrorTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String RPC_URL = "https://auth.saby.test/service/";
    private static final Instant AT = Instant.parse("2026-10-14T08:00:00Z");

    private final GuestVisitRepository visits = mock(GuestVisitRepository.class);
    private final SabyCrmProperties crm = new SabyCrmProperties();
    private MockRestServiceServer server;
    private SabyCrmMirror mirror;

    private static GuestVisit visit(String lead, String phone) {
        return new GuestVisit(7L, 42L, 421L, phone, "AERIS", JourneyStage.REQUESTED, lead, null, 0, AT, AT, null);
    }

    private static GuestVisitEvent event(long id, VisitEventKind kind) {
        return new GuestVisitEvent(id, 7L, kind, kind.axis(), "BOT", null, AT.plusSeconds(id), null);
    }

    @BeforeEach
    void setUp() {
        SabyReservationProperties saby = new SabyReservationProperties();
        saby.setEnabled(true);
        saby.setBaseUrl("https://api.saby.test");
        saby.setAuthUrl(AUTH_URL);
        saby.setAppClientId("client");
        saby.setAppSecret("secret");
        saby.setSecretKey("service-key");
        saby.setPointId("256");
        crm.setEnabled(true);
        crm.setRegulationId(55L);
        crm.setPhaseDirections("REQUESTED=11,SEATED=14");
        AtomicReference<MockRestServiceServer> ref = new AtomicReference<>();
        SabyApiClient client = new SabyApiClient(saby,
                new RestTemplateBuilder(restTemplate -> ref.set(MockRestServiceServer.bindTo(restTemplate).build())));
        server = ref.get();
        mirror = new SabyCrmMirror(crm, new SabyCrmClient(client, crm), visits, reservationId -> Optional.of("Мария"));
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), requestTo(AUTH_URL))
                .andRespond(withSuccess("{\"token\": \"t\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void firstEventCreatesTheLeadAndMovesItByThePhase() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.REQUESTED)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit(null, "+79120000000")));
        server.expect(once(), requestTo(RPC_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.method").value("CRMLead.insertRecord"))
                .andExpect(jsonPath("$.params['Регламент']").value(55))
                .andExpect(jsonPath("$.params['КонтактноеЛицо']['ФИО']").value("Мария"))
                .andExpect(jsonPath("$.params['КонтактноеЛицо']['Телефон']").value("79120000000"))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"result\":{\"@Документ\":9001}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(RPC_URL))
                .andExpect(jsonPath("$.method").value("CRMEvent.AddEvent"))
                .andExpect(jsonPath("$.params.EventData.d[1]").value(9001))
                .andExpect(jsonPath("$.params.EventData.d[7]").value(11))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"result\":1}", MediaType.APPLICATION_JSON));

        mirror.flush();

        server.verify();
        verify(visits).setSabyLead(7L, "9001");
        verify(visits).markMirrored(1L);
    }

    @Test
    void eventWithoutAPhaseIsACommentOnTheLead() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(2, VisitEventKind.DISH_QUESTION)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit("9001", "+79120000000")));
        server.expect(once(), requestTo(RPC_URL))
                .andExpect(jsonPath("$.method").value("CRMEvent.AddEvent"))
                .andExpect(jsonPath("$.params.EventData.d[5]").value("INFO: DISH_QUESTION"))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"result\":2}", MediaType.APPLICATION_JSON));

        mirror.flush();

        verify(visits, never()).setSabyLead(anyLong(), anyString());
        verify(visits).markMirrored(2L);
    }

    @Test
    void failureMidQueueKeepsTheRestForTheNextRun() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.DISH_QUESTION), event(2, VisitEventKind.SEATED),
                event(3, VisitEventKind.TIP)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit("9001", "+79120000000")));
        server.expect(once(), requestTo(RPC_URL)).andRespond(withSuccess("{\"result\":1}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(RPC_URL)).andRespond(withServerError());

        mirror.flush();

        verify(visits).markMirrored(1L);
        verify(visits, never()).markMirrored(2L);
        verify(visits, never()).markMirrored(3L);
    }

    @Test
    void sabyErrorInTheRpcAnswerStopsTheRun() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.DISH_QUESTION)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit("9001", "+79120000000")));
        server.expect(once(), requestTo(RPC_URL))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"error\":{\"message\":\"Нет прав\"}}", MediaType.APPLICATION_JSON));

        mirror.flush();

        verify(visits, never()).markMirrored(1L);
    }

    @Test
    void guestWithoutPhoneHasNoFakePhone() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.REQUESTED)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit(null, null)));
        server.expect(once(), requestTo(RPC_URL))
                .andExpect(jsonPath("$.params['КонтактноеЛицо']['Телефон']").doesNotExist())
                .andRespond(withSuccess("{\"result\":{\"@Документ\":9002}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(RPC_URL)).andRespond(withSuccess("{\"result\":1}", MediaType.APPLICATION_JSON));

        mirror.flush();

        server.verify();
    }

    @Test
    void disabledMirrorSendsNothing() {
        crm.setEnabled(false);

        mirror.flush();

        verify(visits, never()).unmirrored(50);
    }

    private static GuestVisit visit(long id, String lead) {
        return new GuestVisit(id, 40L + id, 421L, "+79120000000", "AERIS", JourneyStage.REQUESTED, lead, null, 0, AT, AT, null);
    }

    @Test
    void oneVisitsFailureDoesNotHoldBackAnother() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.SEATED),
                new GuestVisitEvent(2L, 8L, VisitEventKind.DISH_QUESTION, VisitEventKind.DISH_QUESTION.axis(), "GUEST", null, AT.plusSeconds(2), null)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit(7L, "9001")));
        when(visits.findById(8L)).thenReturn(Optional.of(visit(8L, "9002")));
        server.expect(once(), requestTo(RPC_URL))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"error\":{\"message\":\"Переход запрещён\"}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(RPC_URL)).andRespond(withSuccess("{\"result\":1}", MediaType.APPLICATION_JSON));

        mirror.flush();

        verify(visits).markMirrorFailed(1L);
        verify(visits).markMirrored(2L);
    }

    @Test
    void aDealWhoseAnswerCannotBeReadIsParkedNotCreatedAgain() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.REQUESTED)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit(null, "+79120000000")));
        server.expect(once(), requestTo(RPC_URL)).andRespond(withSuccess("{\"result\":{}}", MediaType.APPLICATION_JSON));

        mirror.flush();

        verify(visits).setSabyLead(7L, SabyCrmMirror.PENDING);
        verify(visits, never()).setSabyLead(7L, null);

        when(visits.findById(7L)).thenReturn(Optional.of(visit(SabyCrmMirror.PENDING, "+79120000000")));
        mirror.flush();

        server.verify();
        verify(visits, never()).markMirrored(1L);
    }

    @Test
    void aDealSabyRefusedIsTriedAgainNextRun() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.REQUESTED)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit(null, "+79120000000")));
        server.expect(once(), requestTo(RPC_URL))
                .andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"error\":{\"message\":\"Нет прав\"}}", MediaType.APPLICATION_JSON));

        mirror.flush();

        verify(visits).setSabyLead(7L, SabyCrmMirror.PENDING);
        verify(visits).setSabyLead(7L, null);
    }

    @Test
    void theFunnelNeverMovesBack() {
        when(visits.unmirrored(50)).thenReturn(List.of(event(1, VisitEventKind.REQUESTED)));
        when(visits.findById(7L)).thenReturn(Optional.of(visit("9001", "+79120000000")));
        when(visits.sabyStage(7L)).thenReturn(Optional.of(JourneyStage.SEATED));
        server.expect(once(), requestTo(RPC_URL))
                .andExpect(jsonPath("$.params.EventData.d[7]").doesNotExist())
                .andRespond(withSuccess("{\"result\":1}", MediaType.APPLICATION_JSON));

        mirror.flush();

        server.verify();
        verify(visits, never()).setSabyStage(anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
