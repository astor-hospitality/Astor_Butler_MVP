-- Standing zones of the waiters: which zone of the floor (venue_tables.zone: WINDOW, BAR, CENTER_STAGE, …) a waiter
-- usually works, set from the staff chat with /зона. When a guest at a table asks for something and nobody assigned
-- the table for the day (/официант), the waiter on shift whose zone holds the table gets it, and the pick is written
-- to table_waiter_assignments with assigned_by = 'auto' so it stays for the day. A zone may have several waiters.
-- No names or zones are seeded here: the manager fills them in from the staff chat.

CREATE TABLE IF NOT EXISTS waiter_zones (
    tenant VARCHAR(80) NOT NULL,
    staff_id VARCHAR(128) NOT NULL,
    zone VARCHAR(40) NOT NULL,
    assigned_by VARCHAR(160),
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant, staff_id, zone),
    FOREIGN KEY (tenant, staff_id) REFERENCES astor_staff_members(tenant, staff_id)
);

CREATE INDEX IF NOT EXISTS idx_waiter_zones_zone
    ON waiter_zones(tenant, zone);
