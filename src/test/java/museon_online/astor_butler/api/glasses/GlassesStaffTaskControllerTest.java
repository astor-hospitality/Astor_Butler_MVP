package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.api.glasses.tasks.StaffPortalService;
import museon_online.astor_butler.api.glasses.tasks.StaffPortalService.Member;
import museon_online.astor_butler.api.glasses.tasks.StaffScope;
import museon_online.astor_butler.api.glasses.tasks.StaffTask;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskFailure;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskService;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GlassesStaffTaskControllerTest {
    private static final String TOKEN = "unit-relay-token-not-real";
    private static final String ID = "ff5a8c58-bb60-43f4-b542-1e26c8b96581";
    private static final StaffScope WEARER = new StaffScope("AERIS", "manager-1", StaffScope.Role.MANAGER);
    private final ObjectMapper mapper = new ObjectMapper();
    private final StaffPortalService portal = mock(StaffPortalService.class);
    private final TelegramSystemNotifier notifier = mock(TelegramSystemNotifier.class);
    private final GlassesStaffTaskController controller = controller(portal, TOKEN, "MANAGER");

    @SuppressWarnings("unchecked")
    private GlassesStaffTaskController controller(StaffPortalService service, String token, String role) {
        ObjectProvider<StaffPortalService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return new GlassesStaffTaskController(provider, notifier, token, role, mapper);
    }

    private static Member member(String id, String name, String role, String shift) {
        return new Member(id, name, role, true, shift, null);
    }

    private final List<Member> staff = List.of(member("manager-1", "Марина Менеджер", "MANAGER", "OPEN"),
            member("anna", "Анна", "WAITER", "OPEN"), member("ilya", "Илья", "WAITER", "CLOSED"));

    private MockHttpServletRequest request(String json, String token) {
        var request = new MockHttpServletRequest("POST", "/api/internal/glasses/staff-tasks");
        if (token != null) request.addHeader("X-Astor-Relay-Token", token);
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private String payload(Map<String, ?> extra, Map<String, ?> draftExtra) throws Exception {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("assignee", "Анне");
        draft.put("tableCode", "5");
        draft.put("title", "Принести воду");
        draft.put("instruction", "Принести воду на пятый стол");
        draft.put("priority", "NORMAL");
        draft.putAll(draftExtra);
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", ID);
        body.put("tenant", "AERIS");
        body.put("staff", "manager-1");
        body.put("draft", draft);
        body.putAll(extra);
        return mapper.writeValueAsString(body);
    }

    private static StaffTask task(String assignee, String table) {
        return new StaffTask("task-1", "AERIS", "Astor Glass", table, "Принести воду", "Принести воду на пятый стол",
                List.of(new StaffTask.Stage("done", "Выполнить", false, false, 0)), StaffTask.Priority.NORMAL, null,
                assignee, StaffTask.Status.ASSIGNED, 1, null, null);
    }

    @SuppressWarnings("unchecked")
    private String code(Object body) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) body).get("error")).get("code");
    }

    private void ready() {
        when(portal.members("AERIS")).thenReturn(staff);
        when(portal.replay(any(), any())).thenReturn(Optional.empty());
        when(notifier.sendGlassesTask(any(), any(), any(), any(), any())).thenReturn(true);
    }

    @Test void withoutTheSharedTokenNothingIsCreated() throws Exception {
        assertThat(controller.create(request(payload(Map.of(), Map.of()), null)).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.create(request(payload(Map.of(), Map.of()), "wrong-token-value-here")).getStatusCode().value()).isEqualTo(401);
        var unconfigured = controller(portal, "short", "MANAGER");
        var result = unconfigured.create(request(payload(Map.of(), Map.of()), "short"));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(code(result.getBody())).isEqualTo("RELAY_UNAVAILABLE");
        verifyNoInteractions(portal, notifier);
    }

    @Test void whileTasksAreOffTheAnswerIsAClearServiceUnavailable() throws Exception {
        var off = controller(null, TOKEN, "MANAGER");
        var result = off.create(request(payload(Map.of(), Map.of()), TOKEN));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(code(result.getBody())).isEqualTo("TASKS_DISABLED");
        verifyNoInteractions(notifier);
    }

    @Test void theDraftBecomesATaskForTheNamedPersonAndTheTeamIsTold() throws Exception {
        ready();
        when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(task("anna", "5"));

        var result = controller.create(request(payload(Map.of(), Map.of()), TOKEN));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        var created = (GlassesStaffTaskController.Created) result.getBody();
        assertThat(created).isEqualTo(new GlassesStaffTaskController.Created("task-1", "Принести воду", "Анна", "anna", false,
                "Принести воду на пятый стол", "5", "NORMAL", "ASSIGNED"));
        var draft = ArgumentCaptor.forClass(StaffTaskService.Draft.class);
        verify(portal).create(eq(WEARER), eq(ID), draft.capture());
        assertThat(draft.getValue().sourceRef()).isEqualTo("Astor Glass");
        assertThat(draft.getValue().tableCode()).isEqualTo("5");
        assertThat(draft.getValue().title()).isEqualTo("Принести воду");
        assertThat(draft.getValue().instruction()).isEqualTo("Принести воду на пятый стол");
        assertThat(draft.getValue().assigneeStaffId()).isEqualTo("anna");
        assertThat(draft.getValue().priority()).isEqualTo(StaffTask.Priority.NORMAL);
        assertThat(draft.getValue().stages()).singleElement().satisfies(stage -> {
            assertThat(stage.code()).isEqualTo("done");
            assertThat(stage.evidenceRequired()).isFalse();
        });
        verify(notifier).sendGlassesTask("Марина Менеджер", "Принести воду", "Принести воду на пятый стол", "5", "Анна");
    }

    @Test void anUnknownOffShiftOrMissingAssigneeLeavesTheTaskWithTheWearer() throws Exception {
        ready();
        when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(task("manager-1", "-"));
        for (var spoken : new Object[]{"Илье", "Сергею", null}) {
            reset(portal, notifier);
            ready();
            when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(task("manager-1", "-"));
            var draftExtra = new LinkedHashMap<String, Object>();
            draftExtra.put("assignee", spoken);
            draftExtra.put("tableCode", null);
            var result = controller.create(request(payload(Map.of(), draftExtra), TOKEN));
            assertThat(result.getStatusCode().value()).as(String.valueOf(spoken)).isEqualTo(200);
            var created = (GlassesStaffTaskController.Created) result.getBody();
            assertThat(created.self()).isTrue();
            assertThat(created.assignee()).isNull();
            assertThat(created.assigneeStaffId()).isEqualTo("manager-1");
            assertThat(created.tableCode()).as("the placeholder table is not repeated to the wearer").isNull();
            var draft = ArgumentCaptor.forClass(StaffTaskService.Draft.class);
            verify(portal).create(eq(WEARER), eq(ID), draft.capture());
            assertThat(draft.getValue().assigneeStaffId()).isEqualTo("manager-1");
            assertThat(draft.getValue().tableCode()).isEqualTo(GlassesStaffTaskController.NO_TABLE);
            verify(notifier).sendGlassesTask("Марина Менеджер", "Принести воду", "Принести воду на пятый стол", null, "Марина Менеджер");
        }
    }

    @Test void aRoleWordFindsTheOnePersonOnShiftAndAHighPriorityTravels() throws Exception {
        ready();
        when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(task("anna", "5"));
        var result = controller.create(request(payload(Map.of(), Map.of("assignee", "официанту", "priority", "high")), TOKEN));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        var draft = ArgumentCaptor.forClass(StaffTaskService.Draft.class);
        verify(portal).create(eq(WEARER), eq(ID), draft.capture());
        assertThat(draft.getValue().assigneeStaffId()).isEqualTo("anna");
        assertThat(draft.getValue().priority()).isEqualTo(StaffTask.Priority.HIGH);
    }

    @Test void theSameRequestAgainReturnsTheSameTaskAndTellsNobodyTwice() throws Exception {
        ready();
        when(portal.replay("AERIS", ID)).thenReturn(Optional.of(task("anna", "5")));
        var result = controller.create(request(payload(Map.of(), Map.of("title", "совсем другой текст")), TOKEN));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        var created = (GlassesStaffTaskController.Created) result.getBody();
        assertThat(created.taskId()).isEqualTo("task-1");
        assertThat(created.assignee()).isEqualTo("Анна");
        verify(portal, never()).create(any(), any(), any());
        verifyNoInteractions(notifier);
    }

    @Test void theRulesOwnRefusalIsPassedOnWithItsCode() throws Exception {
        ready();
        when(portal.create(any(), any(), any())).thenThrow(new StaffTaskFailure(403, "STAFF_INACTIVE", "The staff account is not active"));
        var result = controller.create(request(payload(Map.of(), Map.of()), TOKEN));
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(code(result.getBody())).isEqualTo("STAFF_INACTIVE");
        verifyNoInteractions(notifier);
    }

    @Test void theWearersRoleComesFromConfigurationNotFromTheRequest() throws Exception {
        ready();
        var hostess = controller(portal, TOKEN, "hostess");
        when(portal.create(any(), eq(ID), any())).thenReturn(task("anna", "5"));
        assertThat(hostess.create(request(payload(Map.of(), Map.of()), TOKEN)).getStatusCode().value()).isEqualTo(200);
        verify(portal).create(eq(new StaffScope("AERIS", "manager-1", StaffScope.Role.HOSTESS)), eq(ID), any());
        assertThatThrownBy(() -> controller(portal, TOKEN, "WAITER")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller(portal, TOKEN, "boss")).isInstanceOf(IllegalArgumentException.class);
        // A role in the body is an unknown field, not an escalation.
        assertThat(controller.create(request(payload(Map.of("role", "MANAGER"), Map.of()), TOKEN)).getStatusCode().value()).isEqualTo(400);
    }

    @Test void refusesMalformedPayloadsBeforeTouchingTheRules() throws Exception {
        var titleless = new LinkedHashMap<String, Object>();
        titleless.put("title", null);
        var rejected = new String[]{
                payload(Map.of("requestId", "nope"), Map.of()),
                payload(Map.of("tenant", ""), Map.of()),
                payload(Map.of("staff", "x".repeat(200)), Map.of()),
                payload(Map.of("draft", "text"), Map.of()),
                payload(Map.of(), titleless),
                payload(Map.of(), Map.of("priority", "URGENT")),
                payload(Map.of(), Map.of("assigneeStaffId", "anna")),
                "{}", "[]", "not json"};
        for (String body : rejected) {
            var result = controller.create(request(body, TOKEN));
            assertThat(result.getStatusCode().value()).as(body).isEqualTo(400);
        }
        verify(portal, never()).create(any(), any(), any());
        verifyNoInteractions(notifier);
    }
}
