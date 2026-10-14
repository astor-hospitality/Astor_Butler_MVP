-- One visit ledger (negotiation plan, task 1): every turn of a guest's visit — the guest, the Concierge and Butler
-- agents, staff and Saby — under one visit id. Concierge turns made before the visit is known carry a session id and
-- are linked to the visit later. payload is JSON as text. Nothing is written while ASTOR_LEDGER_ENABLED is off.

CREATE TABLE IF NOT EXISTS visit_turns (
    id BIGSERIAL PRIMARY KEY,
    visit_id BIGINT,
    session_id VARCHAR(64),
    at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    side VARCHAR(12) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    text VARCHAR(4000),
    payload TEXT,
    goal VARCHAR(8)
);

CREATE INDEX IF NOT EXISTS idx_visit_turns_visit ON visit_turns(visit_id);
CREATE INDEX IF NOT EXISTS idx_visit_turns_session ON visit_turns(session_id);
