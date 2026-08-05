CREATE TABLE IF NOT EXISTS monetary_event (
    event_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('LOSS', 'BILLING', 'EXPIRY')),
    reservation_id TEXT NOT NULL,
    lease_id UUID NOT NULL,
    campaign_id TEXT NOT NULL,
    impression_amount_micros BIGINT NOT NULL CHECK (impression_amount_micros > 0),
    reservation_expires_at TIMESTAMPTZ NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp()
);

CREATE INDEX IF NOT EXISTS monetary_event_lease_idx
    ON monetary_event (lease_id, reservation_id);

CREATE TABLE IF NOT EXISTS reservation_monetary_outcome (
    reservation_id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL UNIQUE REFERENCES monetary_event(event_id),
    kind TEXT NOT NULL CHECK (kind IN ('LOSS', 'BILLING', 'EXPIRY')),
    lease_id UUID NOT NULL,
    campaign_id TEXT NOT NULL,
    impression_amount_micros BIGINT NOT NULL CHECK (impression_amount_micros > 0),
    reservation_expires_at TIMESTAMPTZ NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS reservation_monetary_outcome_lease_idx
    ON reservation_monetary_outcome (lease_id);

CREATE TABLE IF NOT EXISTS monetary_event_conflict (
    conflict_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id TEXT NOT NULL,
    lease_id UUID NOT NULL,
    existing_event_id TEXT NOT NULL,
    incoming_event_id TEXT NOT NULL,
    existing_kind TEXT NOT NULL,
    incoming_kind TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (existing_event_id, incoming_event_id)
);

CREATE INDEX IF NOT EXISTS monetary_event_conflict_lease_idx
    ON monetary_event_conflict (lease_id, reservation_id);
