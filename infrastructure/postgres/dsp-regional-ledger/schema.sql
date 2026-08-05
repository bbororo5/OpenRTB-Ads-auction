CREATE TABLE IF NOT EXISTS regional_campaign_budget (
    campaign_id TEXT PRIMARY KEY,
    responsibility_micros BIGINT NOT NULL CHECK (responsibility_micros > 0),
    available_micros BIGINT NOT NULL CHECK (available_micros >= 0),
    outstanding_micros BIGINT NOT NULL DEFAULT 0 CHECK (outstanding_micros >= 0),
    committed_micros BIGINT NOT NULL DEFAULT 0 CHECK (committed_micros >= 0),
    quarantined_micros BIGINT NOT NULL DEFAULT 0 CHECK (quarantined_micros >= 0),
    next_lease_generation BIGINT NOT NULL DEFAULT 1 CHECK (next_lease_generation > 0),
    campaign_starts_at TIMESTAMPTZ NOT NULL,
    campaign_ends_at TIMESTAMPTZ NOT NULL,
    CHECK (campaign_ends_at > campaign_starts_at),
    CHECK (
        responsibility_micros =
        available_micros + outstanding_micros + committed_micros + quarantined_micros
    )
) WITH (fillfactor = 80);

CREATE TABLE IF NOT EXISTS budget_lease (
    lease_id UUID PRIMARY KEY,
    request_id TEXT NOT NULL UNIQUE,
    requested_micros BIGINT NOT NULL CHECK (requested_micros > 0),
    campaign_id TEXT NOT NULL REFERENCES regional_campaign_budget(campaign_id),
    owner_instance_id TEXT NOT NULL,
    face_value_micros BIGINT NOT NULL CHECK (face_value_micros > 0),
    lease_generation BIGINT NOT NULL CHECK (lease_generation > 0),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    safe_recovery_at TIMESTAMPTZ NOT NULL,
    settlement_generation BIGINT NOT NULL DEFAULT 1 CHECK (settlement_generation > 0),
    settlement_state TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (settlement_state IN ('PENDING', 'CLAIMED', 'SETTLED')),
    claim_generation BIGINT NOT NULL DEFAULT 0 CHECK (claim_generation >= 0),
    claimed_by TEXT,
    claim_until TIMESTAMPTZ,
    committed_micros BIGINT,
    returned_micros BIGINT,
    quarantined_micros BIGINT,
    settled_at TIMESTAMPTZ,
    UNIQUE (campaign_id, lease_generation),
    CHECK (expires_at > issued_at),
    CHECK (safe_recovery_at >= expires_at),
    CHECK (settlement_state <> 'CLAIMED' OR (claimed_by IS NOT NULL AND claim_until IS NOT NULL)),
    CHECK (
        settlement_state <> 'SETTLED'
        OR (
            committed_micros IS NOT NULL
            AND returned_micros IS NOT NULL
            AND quarantined_micros IS NOT NULL
            AND face_value_micros = committed_micros + returned_micros + quarantined_micros
        )
    )
) WITH (fillfactor = 80);

CREATE INDEX IF NOT EXISTS budget_lease_due_idx
    ON budget_lease (safe_recovery_at, lease_id)
    WHERE settlement_state <> 'SETTLED';

CREATE INDEX IF NOT EXISTS budget_lease_campaign_idx
    ON budget_lease (campaign_id, settlement_state);
