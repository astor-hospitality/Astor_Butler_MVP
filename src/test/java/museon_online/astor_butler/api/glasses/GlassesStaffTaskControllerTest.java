package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.api.glasses.tasks.StaffPortalService;
import museon_online.astor_butler.api.glasses.tasks.StaffPortalService.Member;
import museon_online.astor_butler.api.glasses.tasks.StaffScope;
import museon_online.astor_butler.api.glasses.tasks.StaffTask;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskFailure;
import museon_online.astor_butler.api.glasses.tasks.StaffTaskService;
import museon_online.astor_butler.domain.roster.StaffRole;
import museon_online.astor_butler.domain.roster.StaffRoster;
import museon_online.astor_butler.domain.tips.TipStaffMember;
import museon_online.astor_butler.domain.tips.TipsPersonalProperties;
import museon_online.astor_butler.domain.tips.TipsPersonalRepository;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
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

    /* ---------- a short order by dish numbers (#184) ---------- */

    private final TipsPersonalRepository waiters = mock(TipsPersonalRepository.class);
    // 20:30 UTC on the 12th is already the 13th in Yekaterinburg, the venue's shift day.
    private static final Clock EVENING = Clock.fixed(Instant.parse("2026-10-12T20:30:00Z"), ZoneOffset.UTC);
    private static final LocalDate SHIFT_DAY = LocalDate.of(2026, 10, 13);

    @SuppressWarnings("unchecked")
    private GlassesStaffTaskController orderController(TipsPersonalRepository directory) {
        ObjectProvider<StaffPortalService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(portal);
        ObjectProvider<TipsPersonalRepository> waiterProvider = mock(ObjectProvider.class);
        when(waiterProvider.getIfAvailable()).thenReturn(directory);
        ObjectProvider<TipsPersonalProperties> tipsProvider = mock(ObjectProvider.class);
        when(tipsProvider.getIfAvailable()).thenReturn(new TipsPersonalProperties());
        return new GlassesStaffTaskController(provider, notifier, TOKEN, "MANAGER", mapper, waiterProvider, tipsProvider, EVENING);
    }

    private String order() throws Exception {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("assignee", null);
        draft.put("tableCode", "12");
        draft.put("title", "Стол 12: Борщ со сметаной ×1 (голосовой заказ)");
        draft.put("instruction", "забить 14 на 12");
        draft.put("priority", "NORMAL");
        var body = new LinkedHashMap<String, Object>();
        body.put("requestId", ID);
        body.put("tenant", "AERIS");
        body.put("staff", "manager-1");
        body.put("kind", "order");
        body.put("draft", draft);
        return mapper.writeValueAsString(body);
    }

    private static StaffTask orderTask(String assignee) {
        return new StaffTask("task-9", "AERIS", "Astor Glass", "12", "Стол 12: Борщ со сметаной ×1 (голосовой заказ)",
                "забить 14 на 12", List.of(new StaffTask.Stage("done", "Выполнить", false, false, 0)), StaffTask.Priority.NORMAL,
                null, assignee, StaffTask.Status.ASSIGNED, 1, null, null);
    }

    @Test void aShortOrderGoesToTheTablesWaiterOfTheDay() throws Exception {
        ready();
        when(waiters.waiterOfTable("AERIS", "12", SHIFT_DAY))
                .thenReturn(Optional.of(new TipStaffMember("anna", "Анна", "WAITER", true, true, null, null)));
        when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(orderTask("anna"));

        var result = orderController(waiters).create(request(order(), TOKEN));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        var created = (GlassesStaffTaskController.Created) result.getBody();
        assertThat(created.assigneeStaffId()).isEqualTo("anna");
        assertThat(created.assignee()).isEqualTo("Анна");
        assertThat(created.self()).isFalse();
        assertThat(created.tableCode()).isEqualTo("12");
        var draft = ArgumentCaptor.forClass(StaffTaskService.Draft.class);
        verify(portal, times(1)).create(eq(WEARER), eq(ID), draft.capture());
        assertThat(draft.getValue().assigneeStaffId()).isEqualTo("anna");
        assertThat(draft.getValue().title()).isEqualTo("Стол 12: Борщ со сметаной ×1 (голосовой заказ)");
        assertThat(draft.getValue().instruction()).isEqualTo("забить 14 на 12");
        assertThat(draft.getValue().tableCode()).isEqualTo("12");
        assertThat(draft.getValue().priority()).isEqualTo(StaffTask.Priority.NORMAL);
        verify(waiters).waiterOfTable("AERIS", "12", SHIFT_DAY);
        verify(notifier).sendGlassesTask("Марина Менеджер", "Стол 12: Борщ со сметаной ×1 (голосовой заказ)", "забить 14 на 12", "12", "Анна");
    }

    @Test void withoutAWaiterForTheTableTheOrderStaysWithTheWearer() throws Exception {
        // No assignment today, an assigned waiter off shift, a directory that fails, no directory at all: the wearer.
        var offShift = Optional.of(new TipStaffMember("ilya", "Илья", "WAITER", true, false, null, null));
        var unknown = Optional.of(new TipStaffMember("sergey", "Сергей", "WAITER", true, true, null, null));
        for (var answer : new Object[]{Optional.empty(), offShift, unknown, "fails", "none"}) {
            reset(portal, notifier, waiters);
            ready();
            when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(orderTask("manager-1"));
            GlassesStaffTaskController controller;
            if ("fails".equals(answer)) {
                when(waiters.waiterOfTable(any(), any(), any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
                controller = orderController(waiters);
            } else if ("none".equals(answer)) {
                controller = orderController(null);
            } else {
                @SuppressWarnings("unchecked") var waiter = (Optional<TipStaffMember>) answer;
                when(waiters.waiterOfTable("AERIS", "12", SHIFT_DAY)).thenReturn(waiter);
                controller = orderController(waiters);
            }
            var result = controller.create(request(order(), TOKEN));
            assertThat(result.getStatusCode().value()).as(String.valueOf(answer)).isEqualTo(200);
            var created = (GlassesStaffTaskController.Created) result.getBody();
            assertThat(created.self()).as(String.valueOf(answer)).isTrue();
            assertThat(created.assigneeStaffId()).isEqualTo("manager-1");
            var draft = ArgumentCaptor.forClass(StaffTaskService.Draft.class);
            verify(portal).create(eq(WEARER), eq(ID), draft.capture());
            assertThat(draft.getValue().assigneeStaffId()).isEqualTo("manager-1");
        }
    }

    @Test void theKindIsTaskByDefaultAndAnythingElseIsMalformed() throws Exception {
        ready();
        when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(task("anna", "5"));
        // A spoken task with an explicit kind resolves the assignee by name, as before; the directory is not asked.
        var result = orderController(waiters).create(request(payload(Map.of("kind", "task"), Map.of()), TOKEN));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(waiters);
        assertThat(orderController(waiters).create(request(payload(Map.of("kind", "delivery"), Map.of()), TOKEN)).getStatusCode().value()).isEqualTo(400);
        // The controller wired without the directory (the pre-#184 constructor) still takes an order: it stays with the wearer.
        reset(portal, notifier);
        ready();
        when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(orderTask("manager-1"));
        var legacy = controller.create(request(order(), TOKEN));
        assertThat(legacy.getStatusCode().value()).isEqualTo(200);
        assertThat(((GlassesStaffTaskController.Created) legacy.getBody()).self()).isTrue();
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

    /* ---------- a role word and the shift schedule (StaffRoster) ---------- */

    private final List<Member> twoWaitersOpen = List.of(member("manager-1", "Марина Менеджер", "MANAGER", "OPEN"),
            member("anna", "Анна", "WAITER", "OPEN"), member("ilya", "Илья", "WAITER", "OPEN"));

    private StaffRoster roster(String tenant, StaffRoster.OnShift... shifts) {
        StaffRoster roster = mock(StaffRoster.class);
        when(roster.tenant()).thenReturn(tenant);
        when(roster.onShiftNow()).thenReturn(List.of(shifts));
        return roster;
    }

    private static StaffRoster.OnShift waiter(String id, String name) {
        return new StaffRoster.OnShift(id, name, StaffRole.WAITER, SHIFT_DAY, LocalTime.of(10, 0), LocalTime.of(23, 0));
    }

    private String assigneeOf(GlassesStaffTaskController controller, String spoken) throws Exception {
        clearInvocations(portal);
        when(portal.create(eq(WEARER), eq(ID), any())).thenReturn(task("manager-1", "5"));
        assertThat(controller.create(request(payload(Map.of(), Map.of("assignee", spoken)), TOKEN)).getStatusCode().value())
                .isEqualTo(200);
        var draft = ArgumentCaptor.forClass(StaffTaskService.Draft.class);
        verify(portal).create(eq(WEARER), eq(ID), draft.capture());
        return draft.getValue().assigneeStaffId();
    }

    @Test void aRoleWordGoesToTheWaiterTheScheduleHasOnShift() throws Exception {
        ready();
        when(portal.members("AERIS")).thenReturn(twoWaitersOpen);
        assertThat(assigneeOf(controller, "официанту")).as("two open shifts, no schedule: stays with the wearer")
                .isEqualTo("manager-1");

        controller.setRoster(roster("AERIS", waiter("ilya", "Илья")));
        assertThat(assigneeOf(controller, "официанту")).isEqualTo("ilya");
        assertThat(assigneeOf(controller, "Анне")).as("a name is not overridden by the schedule").isEqualTo("anna");
    }

    @Test void twoOnTheScheduleAnotherVenueOrAnUnreadableScheduleGuessNobody() throws Exception {
        ready();
        when(portal.members("AERIS")).thenReturn(twoWaitersOpen);

        controller.setRoster(roster("AERIS", waiter("anna", "Анна"), waiter("ilya", "Илья")));
        assertThat(assigneeOf(controller, "официанту")).isEqualTo("manager-1");

        controller.setRoster(roster("OTHER", waiter("ilya", "Илья")));
        assertThat(assigneeOf(controller, "официанту")).as("another venue's schedule is not used").isEqualTo("manager-1");

        StaffRoster broken = mock(StaffRoster.class);
        when(broken.tenant()).thenReturn("AERIS");
        when(broken.onShiftNow()).thenThrow(new IllegalStateException("db down"));
        controller.setRoster(broken);
        assertThat(assigneeOf(controller, "официанту")).isEqualTo("manager-1");
    }

    @Test void anEmptyScheduleLeavesTheOldMatching() throws Exception {
        ready();
        controller.setRoster(roster("AERIS"));
        // The default staff: Анна is the one waiter with an open shift.
        assertThat(assigneeOf(controller, "официанту")).isEqualTo("anna");
    }
}
