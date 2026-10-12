package museon_online.astor_butler.api.glasses.tasks;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Rows of astor_staff_task_evidence. A row is the only link from a stored object to its task, its stage, the
 * task version the photo produced and the audit line of the upload; without a row an object is unreachable.
 * Written inside the portal's tenant-locked transaction, after the file itself is in the storage.
 */
final class JdbcStaffEvidenceRecords {
    static final int TASK_LIMIT = 100;
    private static final String COLUMNS = "evidence_id,task_id,stage_code,version_after,event_id,uploaded_by,content_type,"
            + "size_bytes,sha256,captured_at,received_at,expires_at,object_key";
    private static final RowMapper<Row> ROW = (rs, n) -> new Row(new StaffEvidence(rs.getString(1), rs.getString(2),
            rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8),
            rs.getString(9), instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12))),
            rs.getString(13));
    private final JdbcTemplate jdbc;

    /** A photo's record together with the key of its object, which never leaves the server. */
    record Row(StaffEvidence evidence, String objectKey) { }

    JdbcStaffEvidenceRecords(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void insert(String tenant, StaffEvidence evidence, String objectKey) {
        jdbc.update("INSERT INTO astor_staff_task_evidence(evidence_id,tenant,task_id,stage_code,version_after,event_id,"
                        + "uploaded_by,content_type,size_bytes,sha256,object_key,captured_at,received_at,expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                evidence.evidenceId(), tenant, evidence.taskId(), evidence.stageCode(), evidence.versionAfter(),
                evidence.eventId(), evidence.uploadedBy(), evidence.contentType(), evidence.size(), evidence.sha256(),
                objectKey, timestamp(evidence.capturedAt()), timestamp(evidence.receivedAt()), timestamp(evidence.expiresAt()));
    }

    Optional<Row> find(String tenant, String taskId, String evidenceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM astor_staff_task_evidence WHERE tenant=? AND task_id=? AND evidence_id=?",
                ROW, tenant, taskId, evidenceId).stream().findFirst();
    }

    Optional<Row> byEvent(String tenant, String eventId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM astor_staff_task_evidence WHERE tenant=? AND event_id=?",
                ROW, tenant, eventId).stream().findFirst();
    }

    /** The photos of one task, oldest first. Bounded like the task history: a pilot list, not an export. */
    List<StaffEvidence> byTask(String tenant, String taskId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM astor_staff_task_evidence WHERE tenant=? AND task_id=? "
                        + "ORDER BY received_at, evidence_id LIMIT " + TASK_LIMIT, ROW, tenant, taskId)
                .stream().map(Row::evidence).toList();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
