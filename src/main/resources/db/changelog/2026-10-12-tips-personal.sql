-- Personal tips for the waiter of the table (#161). The staff directory astor_staff_members gets the waiter's own
-- tip link (an SBP link to a private person: no commission on withdrawal, chosen by the venue) and a short note for
-- the manager. A waiter is tied to a table for one shift day; the latest assignment of a table wins.
-- No links, names or assignments are seeded here: the manager fills them in from the staff chat.

ALTER TABLE astor_staff_members ADD COLUMN IF NOT EXISTS tip_link TEXT;
ALTER TABLE astor_staff_members ADD COLUMN IF NOT EXISTS tip_note VARCHAR(160);

CREATE TABLE IF NOT EXISTS table_waiter_assignments (
    tenant VARCHAR(80) NOT NULL,
    table_code VARCHAR(32) NOT NULL,
    shift_date DATE NOT NULL,
    staff_id VARCHAR(128) NOT NULL,
    assigned_by VARCHAR(160),
    assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant, table_code, shift_date),
    FOREIGN KEY (tenant, staff_id) REFERENCES astor_staff_members(tenant, staff_id)
);

CREATE INDEX IF NOT EXISTS idx_table_waiter_assignments_shift
    ON table_waiter_assignments(tenant, shift_date, table_code);
