-- Proof photos of staff tasks (docs/operations/STAFF_TASK_EVIDENCE.md). The bytes live in private object
-- storage. A row here is the only link from a stored object to its task, its stage, the task version the
-- photo produced and the audit line of the upload. Nothing is seeded and no existing table is altered.
CREATE TABLE astor_staff_task_evidence (
    evidence_id VARCHAR(64) PRIMARY KEY,
    tenant VARCHAR(80) NOT NULL,
    task_id VARCHAR(64) NOT NULL REFERENCES astor_staff_tasks(task_id),
    stage_code VARCHAR(32) NOT NULL,
    version_after BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    uploaded_by VARCHAR(128) NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 VARCHAR(64) NOT NULL,
    object_key VARCHAR(255) NOT NULL UNIQUE,
    captured_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (tenant, event_id),
    FOREIGN KEY (tenant, event_id) REFERENCES astor_staff_task_audit(tenant, event_id)
);
CREATE INDEX astor_staff_task_evidence_task_idx ON astor_staff_task_evidence(tenant, task_id, received_at);
