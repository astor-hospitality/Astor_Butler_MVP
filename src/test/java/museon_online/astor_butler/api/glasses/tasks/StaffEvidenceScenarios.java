package museon_online.astor_butler.api.glasses.tasks;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.heic;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.jpeg;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.png;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proof photos from the HTTP route down to the rows, with the real portal service, the real migrations and
 * a storage kept in memory. The same scenarios run on H2 in PostgreSQL mode everywhere and on a real
 * PostgreSQL where the staff fixture database is given (CI): see the two subclasses.
 *
 * The caller comes from a header instead of a signed token: the token itself is StaffPortalSecurityTest's job.
 */
abstract class StaffEvidenceScenarios {
    static final int LIMIT = 4096;
    static final String MANAGER = "AERIS|manager|manager", HOSTESS = "AERIS|maria|hostess";
    static final String ANNA = "AERIS|anna|waiter", ILYA = "AERIS|ilya|waiter";
    static final String OTHER_MANAGER = "OTHER|boss|manager", OTHER_ANNA = "OTHER|anna|waiter";
    private static final StaffScope BOSS = new StaffScope("AERIS", "manager", StaffScope.Role.MANAGER);
    private static final StaffScope WAITER = new StaffScope("AERIS", "anna", StaffScope.Role.WAITER);

    final InMemoryStaffEvidenceStorage storage = new InMemoryStaffEvidenceStorage();
    final TestClock clock = new TestClock();
    JdbcTemplate jdbc;
    StaffPortalService portal;
    MockMvc mvc;
    private int events;

    /** A database with the staff portal migration and the evidence migration applied, and nothing else. */
    abstract DataSource database() throws Exception;

    @BeforeEach void open() throws Exception {
        DataSource source = database();
        jdbc = new JdbcTemplate(source);
        var settings = new StaffEvidenceSettings(storage, LIMIT,
                Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"), Duration.ofDays(30));
        portal = new StaffPortalService(jdbc, new DataSourceTransactionManager(source), settings, clock);
        mvc = mvc(portal);
        // Plain rows instead of portal.member(): the directory is not what is tested here.
        for (String[] member : new String[][]{{"AERIS", "manager", "MANAGER"}, {"AERIS", "maria", "HOSTESS"},
                {"AERIS", "anna", "WAITER"}, {"AERIS", "ilya", "WAITER"}, {"OTHER", "boss", "MANAGER"}, {"OTHER", "anna", "WAITER"}}) {
            jdbc.update("INSERT INTO astor_staff_members(tenant,staff_id,display_name,role,active,shift_open) VALUES (?,?,?,?,TRUE,TRUE)",
                    member[0], member[1], member[1], member[2]);
        }
    }

    private static MockMvc mvc(StaffPortalService portal) {
        return MockMvcBuilders.standaloneSetup(new StaffPortalController(portal)).setCustomArgumentResolvers(new Caller()).build();
    }

    /* ---------- Writing ---------- */

    @Test void aStageThatRequiresAPhotoClosesOnlyAfterTheFileIsStored() throws Exception {
        StaffTask task = atTheSettingStage();
        byte[] photo = jpeg(900);

        command(ANNA, task, "STAGE_DONE", 3, "setting").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_REQUIRED"));

        storage.down = true;
        upload(ANNA, task, "photo-1", "setting", "image/jpeg", photo).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_STORAGE_UNAVAILABLE"));
        assertThat(storage.objects).isEmpty();
        assertThat(count("astor_staff_task_evidence")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM astor_staff_task_audit WHERE type='EVIDENCE'", Integer.class)).isZero();
        command(ANNA, task, "STAGE_DONE", 3, "setting").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EVIDENCE_REQUIRED"));

        storage.down = false;
        upload(ANNA, task, "photo-1", "setting", "image/jpeg", photo).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.task.version").value(4))
                .andExpect(jsonPath("$.task.stages[1].evidence").value(1))
                .andExpect(jsonPath("$.task.stages[1].done").value(false))
                .andExpect(jsonPath("$.evidence.taskId").value(task.taskId()))
                .andExpect(jsonPath("$.evidence.stageCode").value("setting"))
                .andExpect(jsonPath("$.evidence.versionAfter").value(4))
                .andExpect(jsonPath("$.evidence.uploadedBy").value("anna"))
                .andExpect(jsonPath("$.evidence.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.evidence.size").value(900))
                .andExpect(jsonPath("$.evidence.sha256").value(StaffEvidenceStorage.sha256(photo)))
                .andExpect(jsonPath("$.evidence.objectKey").doesNotExist())
                .andExpect(jsonPath("$.evidence.tenant").doesNotExist());
        assertThat(storage.objects).hasSize(1);
        String key = storage.objects.keySet().iterator().next();
        assertThat(key).startsWith(StaffEvidenceStorage.taskPrefix("AERIS", task.taskId())).endsWith(".jpg").doesNotContain("AERIS");
        assertThat(storage.objects.get(key)).isEqualTo(photo);
        assertThat(storage.types.get(key)).isEqualTo("image/jpeg");

        command(ANNA, task, "STAGE_DONE", 4, "setting").andExpect(status().isOk())
                .andExpect(jsonPath("$.stages[1].done").value(true));
        command(ANNA, task, "COMPLETE", 5, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test void onlyTheAssigneeWithAnOpenShiftWritesAndNothingIsStoredForAnyoneElse() throws Exception {
        StaffTask task = atTheSettingStage();
        byte[] photo = jpeg(900);

        refused(upload(ILYA, task, event(), "setting", "image/jpeg", photo), 403, "FORBIDDEN");
        refused(upload(MANAGER, task, event(), "setting", "image/jpeg", photo), 403, "FORBIDDEN");
        refused(upload(HOSTESS, task, event(), "setting", "image/jpeg", photo), 403, "FORBIDDEN");
        // Another venue, even under the very same staff id, sees no such task.
        refused(upload(OTHER_ANNA, task, event(), "setting", "image/jpeg", photo), 404, "NOT_FOUND");
        refused(upload(OTHER_MANAGER, task, event(), "setting", "image/jpeg", photo), 404, "NOT_FOUND");
        refused(upload("AERIS|ghost|waiter", task, event(), "setting", "image/jpeg", photo), 403, "STAFF_INACTIVE");

        portal.shift(BOSS, "anna", false, null);
        refused(upload(ANNA, task, event(), "setting", "image/jpeg", photo), 403, "SHIFT_CLOSED");
        portal.shift(BOSS, "anna", true, null);
        portal.reassign(BOSS, task.taskId(), event(), 3, "ilya");
        refused(upload(ANNA, task, event(), "setting", "image/jpeg", photo), 403, "FORBIDDEN");

        assertThat(storage.puts).isZero();
        assertThat(count("astor_staff_task_evidence")).isZero();
        assertThat(portal.dashboard(BOSS).tasks().getFirst().stages()).extracting(StaffTask.Stage::evidence).containsOnly(0);
    }

    @Test void aFileThatIsNotAnAcceptedPhotoOrIsTooLargeIsRefused() throws Exception {
        StaffTask task = atTheSettingStage();
        byte[] text = "not a photo, just text that says it is one".getBytes(StandardCharsets.UTF_8);
        byte[] gif = "GIF89a-------------------------------".getBytes(StandardCharsets.US_ASCII);

        refused(upload(ANNA, task, event(), "setting", "image/jpeg", text), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(upload(ANNA, task, event(), "setting", "image/jpeg", png()), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(upload(ANNA, task, event(), "setting", "image/gif", gif), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(upload(ANNA, task, event(), "setting", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8)), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(upload(ANNA, task, event(), "setting", "application/json", "{}".getBytes(StandardCharsets.UTF_8)), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(upload(ANNA, task, event(), "setting", "image/heic", InMemoryStaffEvidenceStorage.isoMedia("isom")), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(upload(ANNA, task, event(), "setting", "image/jpeg", jpeg(LIMIT + 1)), 413, "PAYLOAD_TOO_LARGE");
        refused(upload(ANNA, task, event(), "setting", "image/jpeg", new byte[0]), 400, "MALFORMED_REQUEST");
        refused(upload(ANNA, task, "", "setting", "image/jpeg", jpeg(900)), 400, "MALFORMED_REQUEST");
        refused(upload(ANNA, task, event(), "", "image/jpeg", jpeg(900)), 400, "MALFORMED_REQUEST");
        refused(mvc.perform(post("/api/staff/tasks/{task}/evidence", task.taskId()).queryParam("eventId", event())
                .queryParam("stageCode", "setting").queryParam("capturedAt", "yesterday")
                .header("X-Test-Staff", ANNA).contentType("image/jpeg").content(jpeg(900))), 400, "MALFORMED_REQUEST");
        assertThat(storage.puts).isZero();
        assertThat(count("astor_staff_task_evidence")).isZero();

        upload(ANNA, task, event(), "setting", "image/jpeg", jpeg(LIMIT)).andExpect(status().isOk());
        upload(ANNA, task, event(), "setting", "image/png", png()).andExpect(status().isOk());
        upload(ANNA, task, event(), "setting", "image/webp", webp()).andExpect(status().isOk());
        upload(ANNA, task, event(), "setting", "image/heic", heic()).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.stages[1].evidence").value(4));
        assertThat(storage.objects.keySet()).extracting(key -> key.substring(key.lastIndexOf('.')))
                .containsExactly(".jpg", ".png", ".webp", ".heic");
    }

    @Test void aPhotoForATaskNotInWorkOrAClosedOrUnknownStageNeverReachesTheStorage() throws Exception {
        StaffTask fresh = portal.create(BOSS, event(), draft("7"));
        refused(upload(ANNA, fresh, event(), "setting", "image/jpeg", jpeg(900)), 409, "INVALID_TRANSITION");

        StaffTask task = atTheSettingStage();
        refused(upload(ANNA, task, event(), "dessert", "image/jpeg", jpeg(900)), 400, "MALFORMED_REQUEST");
        refused(upload(ANNA, task, event(), "seats", "image/jpeg", jpeg(900)), 409, "STAGE_CLOSED");
        refused(upload(ANNA, task, event(), "x".repeat(33), "image/jpeg", jpeg(900)), 400, "MALFORMED_REQUEST");
        refused(mvc.perform(post("/api/staff/tasks/no-such-task/evidence").queryParam("eventId", event())
                .queryParam("stageCode", "setting").header("X-Test-Staff", ANNA).contentType("image/jpeg").content(jpeg(900))), 404, "NOT_FOUND");

        portal.cancel(BOSS, task.taskId(), event(), 3);
        refused(upload(ANNA, task, event(), "setting", "image/jpeg", jpeg(900)), 409, "TASK_CLOSED");
        assertThat(storage.puts).isZero();
    }

    /* ---------- The link to the stage version and to the audit ---------- */

    @Test void theStoredFileIsTiedToTheStageVersionAndTheAuditTrail() throws Exception {
        StaffTask task = atTheSettingStage();
        String captured = "2026-10-11T08:59:30.250Z";
        String body = mvc.perform(post("/api/staff/tasks/{task}/evidence", task.taskId()).queryParam("eventId", "photo-1")
                        .queryParam("stageCode", "setting").queryParam("capturedAt", captured)
                        .header("X-Test-Staff", ANNA).contentType("image/jpeg").content(jpeg(900)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidence.eventId").value("photo-1"))
                .andExpect(jsonPath("$.evidence.capturedAt").value(captured))
                .andExpect(jsonPath("$.evidence.receivedAt").value("2026-10-11T09:00:00.123Z"))
                .andExpect(jsonPath("$.evidence.expiresAt").value("2026-11-10T09:00:00.123Z"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.evidence.evidenceId");

        Map<String, Object> row = jdbc.queryForMap("SELECT e.tenant, e.task_id, e.stage_code, e.version_after, e.object_key, "
                + "a.type, a.actor, a.task_id AS audit_task, a.version_after AS audit_version "
                + "FROM astor_staff_task_evidence e JOIN astor_staff_task_audit a ON a.tenant=e.tenant AND a.event_id=e.event_id "
                + "WHERE e.evidence_id=?", id);
        assertThat(row.get("tenant")).isEqualTo("AERIS");
        assertThat(row.get("task_id")).isEqualTo(task.taskId());
        assertThat(row.get("stage_code")).isEqualTo("setting");
        assertThat(((Number) row.get("version_after")).longValue()).isEqualTo(4);
        assertThat(row.get("type")).isEqualTo("EVIDENCE");
        assertThat(row.get("actor")).isEqualTo("anna");
        assertThat(row.get("audit_task")).isEqualTo(task.taskId());
        assertThat(((Number) row.get("audit_version")).longValue()).isEqualTo(4);
        assertThat(storage.objects).containsOnlyKeys((String) row.get("object_key"));

        mvc.perform(get("/api/admin/staff-tasks/{task}/history", task.taskId()).header("X-Test-Staff", MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='EVIDENCE')].version").value(4))
                .andExpect(jsonPath("$[?(@.type=='EVIDENCE')].actor").value("anna"));
    }

    @Test void aRepeatedUploadIsCountedOnceAndAnotherFileUnderItsEventIdIsAConflict() throws Exception {
        StaffTask task = atTheSettingStage();
        byte[] photo = jpeg(900);
        String first = upload(ANNA, task, "photo-1", "setting", "image/jpeg", photo).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String again = upload(ANNA, task, "photo-1", "setting", "image/jpeg", photo).andExpect(status().isOk())
                .andExpect(jsonPath("$.task.version").value(4))
                .andExpect(jsonPath("$.task.stages[1].evidence").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(again).isEqualTo(first);
        assertThat(storage.puts).isEqualTo(1);
        assertThat(count("astor_staff_task_evidence")).isEqualTo(1);

        refused(upload(ANNA, task, "photo-1", "setting", "image/jpeg", jpeg(901)), 409, "EVENT_CONFLICT");
        // "accept" is the event id of the ACCEPT command of this task: it cannot become a photo.
        refused(upload(ANNA, task, "accept", "setting", "image/jpeg", photo), 409, "EVENT_CONFLICT");
        assertThat(storage.puts).isEqualTo(1);

        // A repeat still checks who is asking: after the shift is closed the stored answer is not handed out.
        portal.shift(BOSS, "anna", false, null);
        refused(upload(ANNA, task, "photo-1", "setting", "image/jpeg", photo), 403, "SHIFT_CLOSED");
    }

    /* ---------- Reading ---------- */

    @Test void theManagerTheHostessAndTheAssigneeReadAndNobodyElseDoes() throws Exception {
        StaffTask task = atTheSettingStage();
        byte[] photo = jpeg(900);
        String id = JsonPath.read(upload(ANNA, task, "photo-1", "setting", "image/jpeg", photo).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.evidence.evidenceId");
        String adminList = "/api/admin/staff-tasks/" + task.taskId() + "/evidence", adminFile = adminList + "/" + id;
        String ownList = "/api/staff/tasks/" + task.taskId() + "/evidence", ownFile = ownList + "/" + id;

        for (String reader : List.of(MANAGER, HOSTESS)) {
            mvc.perform(get(adminList).header("X-Test-Staff", reader)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].evidenceId").value(id))
                    .andExpect(jsonPath("$.items[0].stageCode").value("setting"))
                    .andExpect(jsonPath("$.items[0].objectKey").doesNotExist());
            mvc.perform(get(adminFile).header("X-Test-Staff", reader)).andExpect(status().isOk())
                    .andExpect(content().bytes(photo)).andExpect(header().string("Content-Type", "image/jpeg"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        mvc.perform(get(ownList).header("X-Test-Staff", ANNA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].evidenceId").value(id));
        mvc.perform(get(ownFile).header("X-Test-Staff", ANNA)).andExpect(status().isOk()).andExpect(content().bytes(photo));

        // The same venue, but neither the assignee nor a manager.
        refused(mvc.perform(get(ownList).header("X-Test-Staff", ILYA)), 403, "FORBIDDEN");
        refused(mvc.perform(get(ownFile).header("X-Test-Staff", ILYA)), 403, "FORBIDDEN");
        refused(mvc.perform(get(adminList).header("X-Test-Staff", ILYA)), 403, "FORBIDDEN");
        refused(mvc.perform(get(adminFile).header("X-Test-Staff", ANNA)), 403, "FORBIDDEN");
        refused(mvc.perform(get(ownFile).header("X-Test-Staff", MANAGER)), 403, "FORBIDDEN");
        // Another venue: the task reads as missing, for its manager and for a waiter with the same staff id.
        refused(mvc.perform(get(adminList).header("X-Test-Staff", OTHER_MANAGER)), 404, "NOT_FOUND");
        refused(mvc.perform(get(adminFile).header("X-Test-Staff", OTHER_MANAGER)), 404, "NOT_FOUND");
        refused(mvc.perform(get(ownFile).header("X-Test-Staff", OTHER_ANNA)), 404, "NOT_FOUND");
        // A photo is reached only through its own task.
        StaffTask second = portal.create(BOSS, event(), draft("7"));
        refused(mvc.perform(get("/api/admin/staff-tasks/" + second.taskId() + "/evidence/" + id).header("X-Test-Staff", MANAGER)), 404, "NOT_FOUND");
        refused(mvc.perform(get(adminList + "/no-such-photo").header("X-Test-Staff", MANAGER)), 404, "NOT_FOUND");

        portal.shift(BOSS, "anna", false, null);
        refused(mvc.perform(get(ownFile).header("X-Test-Staff", ANNA)), 403, "SHIFT_CLOSED");
        portal.shift(BOSS, "anna", true, null);
        // After a reassignment the photos follow the task: the new assignee reads them, the old one does not.
        portal.reassign(BOSS, task.taskId(), event(), 4, "ilya");
        refused(mvc.perform(get(ownFile).header("X-Test-Staff", ANNA)), 403, "FORBIDDEN");
        mvc.perform(get(ownFile).header("X-Test-Staff", ILYA)).andExpect(status().isOk()).andExpect(content().bytes(photo));
    }

    @Test void aPhotoPastItsRetentionIsNotServedAndAnAlteredOneNeverIs() throws Exception {
        StaffTask task = atTheSettingStage();
        String id = JsonPath.read(upload(ANNA, task, "photo-1", "setting", "image/jpeg", jpeg(900)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.evidence.evidenceId");
        String list = "/api/admin/staff-tasks/" + task.taskId() + "/evidence", file = list + "/" + id;
        String key = storage.objects.keySet().iterator().next();

        storage.objects.put(key, jpeg(900 + 1));
        refused(mvc.perform(get(file).header("X-Test-Staff", MANAGER)), 503, "EVIDENCE_STORAGE_UNAVAILABLE");
        byte[] other = jpeg(900);
        other[100] ^= 1;
        storage.objects.put(key, other);
        refused(mvc.perform(get(file).header("X-Test-Staff", MANAGER)), 503, "EVIDENCE_STORAGE_UNAVAILABLE");
        storage.objects.put(key, jpeg(900));
        mvc.perform(get(file).header("X-Test-Staff", MANAGER)).andExpect(status().isOk());

        clock.now = clock.now.plus(Duration.ofDays(30)).minusMillis(1);
        mvc.perform(get(file).header("X-Test-Staff", MANAGER)).andExpect(status().isOk());
        clock.now = clock.now.plusMillis(1);
        refused(mvc.perform(get(file).header("X-Test-Staff", MANAGER)), 410, "EVIDENCE_EXPIRED");
        // The record of the upload stays in the task's trail after the file is gone.
        mvc.perform(get(list).header("X-Test-Staff", MANAGER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].evidenceId").value(id));

        clock.now = clock.now.minus(Duration.ofDays(10));
        storage.objects.clear();
        refused(mvc.perform(get(file).header("X-Test-Staff", MANAGER)), 410, "EVIDENCE_EXPIRED");
    }

    /* ---------- No storage ---------- */

    @Test void withoutAStorageEveryEvidenceRouteAnswers503AndAStageThatNeedsAPhotoStaysOpen() throws Exception {
        StaffTask task = atTheSettingStage();
        MockMvc off = mvc(new StaffPortalService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource())));
        String own = "/api/staff/tasks/" + task.taskId() + "/evidence", admin = "/api/admin/staff-tasks/" + task.taskId() + "/evidence";

        off.perform(post(own).queryParam("eventId", event()).queryParam("stageCode", "setting")
                        .header("X-Test-Staff", ANNA).contentType("image/jpeg").content(jpeg(900)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("EVIDENCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.message").value("Photo storage is not connected yet"));
        for (String route : List.of(own, own + "/any")) {
            off.perform(get(route).header("X-Test-Staff", ANNA)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("EVIDENCE_UNAVAILABLE"));
        }
        for (String route : List.of(admin, admin + "/any")) {
            off.perform(get(route).header("X-Test-Staff", MANAGER)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.error.code").value("EVIDENCE_UNAVAILABLE"));
        }
        // Who is asking is still checked first, as before.
        off.perform(post(own).queryParam("eventId", event()).queryParam("stageCode", "setting")
                        .header("X-Test-Staff", "AERIS|ghost|waiter").contentType("image/jpeg").content(jpeg(900)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("STAFF_INACTIVE"));
        off.perform(post("/api/staff/tasks/{task}/commands", task.taskId()).header("X-Test-Staff", ANNA).contentType("application/json")
                        .content("{\"eventId\":\"" + event() + "\",\"type\":\"STAGE_DONE\",\"expectedVersion\":3,\"stageCode\":\"setting\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("EVIDENCE_REQUIRED"));
        assertThat(storage.puts).isZero();
        assertThat(count("astor_staff_task_evidence")).isZero();
    }

    /* ---------- Helpers ---------- */

    private String event() {
        return "event-" + ++events;
    }

    private static StaffTaskService.Draft draft(String table) {
        return new StaffTaskService.Draft("Manager", table, "Prepare table", "Check the setting",
                List.of(new StaffTaskService.StageDraft("seats", "Seats", false), new StaffTaskService.StageDraft("setting", "Setting", true)),
                StaffTask.Priority.NORMAL, null, "anna");
    }

    /** Anna's task, accepted, its first stage done: version 3, and the next stage requires a photo. */
    private StaffTask atTheSettingStage() {
        StaffTask task = portal.create(BOSS, "create", draft("13"));
        portal.command(WAITER, task.taskId(), "accept", StaffTaskService.Command.ACCEPT, 1, null);
        return portal.command(WAITER, task.taskId(), "seats", StaffTaskService.Command.STAGE_DONE, 2, "seats");
    }

    private ResultActions upload(String caller, StaffTask task, String eventId, String stageCode, String type, byte[] photo) throws Exception {
        return mvc.perform(post("/api/staff/tasks/{task}/evidence", task.taskId()).queryParam("eventId", eventId)
                .queryParam("stageCode", stageCode).header("X-Test-Staff", caller).contentType(type).content(photo));
    }

    private ResultActions command(String caller, StaffTask task, String type, long version, String stage) throws Exception {
        return mvc.perform(post("/api/staff/tasks/{task}/commands", task.taskId()).header("X-Test-Staff", caller)
                .contentType("application/json").content("{\"eventId\":\"" + event() + "\",\"type\":\"" + type
                        + "\",\"expectedVersion\":" + version + (stage == null ? "" : ",\"stageCode\":\"" + stage + "\"") + "}"));
    }

    private static void refused(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** Who is calling, as "venue|staff id|role": what a verified token would have said. */
    private static final class Caller implements HandlerMethodArgumentResolver {
        @Override public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType() == Jwt.class;
        }

        @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                                NativeWebRequest request, WebDataBinderFactory binders) {
            String[] caller = request.getHeader("X-Test-Staff").split("\\|");
            return Jwt.withTokenValue("test").header("alg", "none").subject(caller[1]).audience(List.of("astor-api"))
                    .claim("tenant", caller[0]).claim("realm_access", Map.of("roles", List.of("astor-" + caller[2]))).build();
        }
    }

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-11T09:00:00.123456789Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
