-- No employees, roles, passwords or demo assignments are seeded.
CREATE TABLE astor_staff_tenant_locks (tenant VARCHAR(80) PRIMARY KEY);
CREATE TABLE astor_staff_members (
    tenant VARCHAR(80) NOT NULL,
    staff_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('WAITER', 'HOSTESS', 'MANAGER')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    shift_open BOOLEAN NOT NULL DEFAULT FALSE,
    device_id VARCHAR(128),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant, staff_id)
);
CREATE TABLE astor_staff_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    tenant VARCHAR(80) NOT NULL,
    assignee_staff_id VARCHAR(128) NOT NULL,
    version BIGINT NOT NULL,
    payload TEXT NOT NULL,
    changed_at BIGINT GENERATED ALWAYS AS IDENTITY,
    FOREIGN KEY (tenant, assignee_staff_id) REFERENCES astor_staff_members(tenant, staff_id)
);
CREATE INDEX astor_staff_tasks_tenant_idx ON astor_staff_tasks(tenant, changed_at DESC);
CREATE TABLE astor_staff_task_events (
    tenant VARCHAR(80) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    fingerprint TEXT NOT NULL,
    result_payload TEXT NOT NULL,
    PRIMARY KEY (tenant, event_id)
);
CREATE TABLE astor_staff_task_audit (
    tenant VARCHAR(80) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL REFERENCES astor_staff_tasks(task_id),
    actor VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    version_after BIGINT NOT NULL,
    at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant, event_id)
);
CREATE INDEX astor_staff_task_audit_task_idx ON astor_staff_task_audit(tenant, task_id, at);
