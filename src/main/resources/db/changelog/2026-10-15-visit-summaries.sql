-- Visit summaries and agent lessons (negotiation plan, task 3). One model-made summary per finished visit: what the
-- guest wanted, what the venue offered, the outcome, where the talk got stuck and notes for next time. List columns
-- are JSON arrays as text. Lessons for the Concierge and Butler agents wait for a manager's decision on the board;
-- only accepted ones reach the agents. Nothing is written while ASTOR_VISIT_SUMMARY_ENABLED is off.

CREATE TABLE IF NOT EXISTS visit_summaries (
    visit_id BIGINT PRIMARY KEY REFERENCES guest_visits(id),
    guest_wanted TEXT,
    venue_offered TEXT,
    outcome TEXT,
    stuck TEXT,
    next_time_guest TEXT,
    next_time_agents TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_lessons (
    id BIGSERIAL PRIMARY KEY,
    visit_id BIGINT REFERENCES guest_visits(id),
    side VARCHAR(12) NOT NULL,
    text VARCHAR(1000) NOT NULL,
    status VARCHAR(12) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_agent_lessons_status ON agent_lessons(status, side);
CREATE INDEX IF NOT EXISTS idx_agent_lessons_visit ON agent_lessons(visit_id);
