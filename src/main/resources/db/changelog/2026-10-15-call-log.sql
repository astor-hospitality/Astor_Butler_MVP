-- Unified call log (docs/operations/CALL_LOG.md): one row per LLM call, Saby call, guest message and target action,
-- with tokens and cost in rubles. Ids are pseudonymous (salted sha256), no message text is stored.
-- Nothing is written while ASTOR_CALL_LOG_ENABLED is off.
CREATE TABLE IF NOT EXISTS call_log (
    id             BIGSERIAL PRIMARY KEY,
    ts             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    source         VARCHAR(32),
    pseudo_user_id VARCHAR(64),
    correlation_id VARCHAR(64),
    call_type      VARCHAR(24) NOT NULL,
    name           VARCHAR(80),
    result         VARCHAR(24) NOT NULL,
    error_code     VARCHAR(64),
    tokens_in      INTEGER,
    tokens_out     INTEGER,
    cost_rub       NUMERIC(12,4),
    latency_ms     INTEGER
);

CREATE INDEX IF NOT EXISTS idx_call_log_ts ON call_log (ts);
CREATE INDEX IF NOT EXISTS idx_call_log_type_ts ON call_log (call_type, ts);
