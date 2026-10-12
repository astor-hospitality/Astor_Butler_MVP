-- Morning reconfirmation on the day of the visit (#149): instead of the hostess calling every guest at the start
-- of the shift, Butler asks «Всё в силе?» with three buttons. One row per reservation, written before the message
-- leaves, so a restart or a second instance can never ask twice. No guest names or phones here: the row points at
-- the reservation and the chat that already carry them.

CREATE TABLE IF NOT EXISTS table_reservation_morning_checks (
    reservation_id BIGINT PRIMARY KEY,
    chat_id BIGINT NOT NULL,
    visit_date DATE NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE,
    delivery_error VARCHAR(160),
    answer VARCHAR(32),
    answered_at TIMESTAMP WITH TIME ZONE,
    guest_reconfirmed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_table_reservation_morning_checks_visit_date
    ON table_reservation_morning_checks(visit_date);
