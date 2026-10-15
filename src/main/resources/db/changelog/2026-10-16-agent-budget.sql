-- The agent's daily model budget (ASTOR_AGENT_DAILY_BUDGET_RUB): one row per scope and venue day. A model call first
-- reserves its worst-case price (reserved_rub) in one conditional UPDATE, so parallel calls never pass the limit
-- together; the answer then turns its reservation into what the service charged (spent_rub). A call whose price is
-- unknown, that timed out or failed keeps its whole reservation as spent. Rows survive restarts; a reservation left
-- by a crash stays counted until the day ends.

CREATE TABLE IF NOT EXISTS agent_budget_days (
    scope VARCHAR(40) NOT NULL,
    budget_day DATE NOT NULL,
    spent_rub NUMERIC(14, 6) NOT NULL DEFAULT 0,
    reserved_rub NUMERIC(14, 6) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (scope, budget_day)
);
