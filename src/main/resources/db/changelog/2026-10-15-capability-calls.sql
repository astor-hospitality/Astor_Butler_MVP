-- Idempotency of the Capability API (negotiation plan, task 7): a tool that commits the venue to something (a booking,
-- an order, a bill) runs once per Idempotency-Key; a repeated key gets the saved result back. result is the JSON of
-- CapabilityResult, a failure included: it is a fact too.

CREATE TABLE IF NOT EXISTS capability_calls (
    idempotency_key VARCHAR(80) PRIMARY KEY,
    tool VARCHAR(40) NOT NULL,
    result TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
