-- Idempotency of the Capability API (negotiation plan, task 7): a tool that commits the venue to something (a booking,
-- an order, a bill) runs once per Idempotency-Key. The key is claimed before the tool runs (result still null), then
-- the result is written: the JSON of CapabilityResult, a failure included, since it is a fact too. A repeated key gets
-- the saved result back; a key still in progress is refused.

CREATE TABLE IF NOT EXISTS capability_calls (
    idempotency_key VARCHAR(80) PRIMARY KEY,
    tool VARCHAR(40) NOT NULL,
    result TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
