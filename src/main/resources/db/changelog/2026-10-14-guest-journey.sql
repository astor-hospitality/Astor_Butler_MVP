-- Guest journey ledger (spec docs/superpowers/specs/2026-10-13-guest-journey-design.md): one visit per reservation,
-- the events of the eight diploma axes, the useful bot-first messages already sent, and the guest's opt-out.
-- Nothing is written while ASTOR_GUEST_JOURNEY_ENABLED is off.

CREATE TABLE IF NOT EXISTS guest_visits (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL UNIQUE,
    chat_id BIGINT,
    guest_phone VARCHAR(32),
    venue_code VARCHAR(32) NOT NULL,
    stage VARCHAR(24) NOT NULL,
    saby_lead_id VARCHAR(64),
    returned_visit_id BIGINT,
    guest_messages INTEGER NOT NULL DEFAULT 0,
    visit_day TIMESTAMP WITH TIME ZONE,
    opened_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_guest_visits_phone ON guest_visits(guest_phone);
CREATE INDEX IF NOT EXISTS idx_guest_visits_chat ON guest_visits(chat_id);

CREATE TABLE IF NOT EXISTS guest_visit_events (
    id BIGSERIAL PRIMARY KEY,
    visit_id BIGINT NOT NULL REFERENCES guest_visits(id),
    kind VARCHAR(32) NOT NULL,
    axis VARCHAR(16) NOT NULL,
    source VARCHAR(8) NOT NULL,
    note VARCHAR(500),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mirrored_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_guest_visit_events_visit ON guest_visit_events(visit_id);
CREATE INDEX IF NOT EXISTS idx_guest_visit_events_mirrored ON guest_visit_events(mirrored_at);

CREATE TABLE IF NOT EXISTS guest_visit_sent (
    visit_id BIGINT NOT NULL REFERENCES guest_visits(id),
    kind VARCHAR(32) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (visit_id, kind)
);

CREATE TABLE IF NOT EXISTS guest_contact_prefs (
    chat_id BIGINT PRIMARY KEY,
    opted_out_at TIMESTAMP WITH TIME ZONE
);
