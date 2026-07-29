CREATE TABLE ssp_billing_delivery (
    delivery_id UUID PRIMARY KEY,
    proof_digest CHAR(64) NOT NULL,
    provider_id TEXT NOT NULL,
    provider_request_id TEXT NOT NULL,
    imp_id TEXT NOT NULL,
    slot_auction_key TEXT NOT NULL UNIQUE,
    dsp_id TEXT NOT NULL,
    cpm_milli_krw BIGINT NOT NULL CHECK (cpm_milli_krw > 0),
    billing_url TEXT NOT NULL,
    billing_deadline TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'LEASED', 'DELIVERED', 'UNDELIVERED')),
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX ssp_billing_delivery_due_idx
    ON ssp_billing_delivery (state, lease_until, created_at)
    WHERE state IN ('PENDING', 'LEASED');
