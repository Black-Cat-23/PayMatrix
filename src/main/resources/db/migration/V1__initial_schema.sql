-- PayLab / PayMatrix Initial Schema Migration
-- V1__initial_schema.sql

CREATE TABLE test_runs (
    run_id UUID PRIMARY KEY,
    scenario_id VARCHAR(64) NOT NULL,
    scenario_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    webhook_url TEXT,
    response_delay_millis INT,
    finalized_at TIMESTAMPTZ
);

CREATE INDEX idx_test_runs_scenario ON test_runs (scenario_id);

CREATE TABLE provider_payments (
    payment_id VARCHAR(64) PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES test_runs(run_id) ON DELETE CASCADE,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    merchant_reference VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_provider_payments_run_key UNIQUE (run_id, idempotency_key)
);

CREATE INDEX idx_provider_payments_run_id ON provider_payments (run_id);
CREATE INDEX idx_provider_payments_status ON provider_payments (status);

CREATE TABLE run_events (
    event_id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES test_runs(run_id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key VARCHAR(255),
    request_fingerprint VARCHAR(64),
    webhook_event_id UUID,
    http_status INT,
    outcome VARCHAR(64),
    payment_id VARCHAR(64),
    response_delay_millis INT
);

CREATE INDEX idx_run_events_run_id_event_id ON run_events (run_id, event_id);
CREATE UNIQUE INDEX uk_run_events_pre_commit_timeout ON run_events (run_id) WHERE event_type = 'PRE_COMMIT_TIMEOUT_INJECTED';

CREATE TABLE webhook_events (
    event_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES test_runs(run_id) ON DELETE CASCADE,
    payment_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_webhook_events_run_payment_type UNIQUE (run_id, payment_id, event_type)
);

CREATE INDEX idx_webhook_events_run_id ON webhook_events (run_id);

CREATE TABLE webhook_deliveries (
    event_id UUID PRIMARY KEY REFERENCES webhook_events(event_id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    due_at TIMESTAMPTZ NOT NULL,
    target_delivery_count INT NOT NULL DEFAULT 1,
    max_failure_retries INT NOT NULL DEFAULT 0,
    signature_mode VARCHAR(32) NOT NULL DEFAULT 'VALID',
    attempt_count INT NOT NULL DEFAULT 0,
    acknowledged_delivery_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    last_http_status INT,
    claim_token UUID,
    claim_until TIMESTAMPTZ
);

CREATE INDEX idx_webhook_deliveries_claim ON webhook_deliveries (due_at, event_id)
    WHERE status = 'PENDING' OR status = 'IN_PROGRESS';

CREATE TABLE webhook_delivery_attempts (
    attempt_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES webhook_events(event_id) ON DELETE CASCADE,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    http_status INT,
    outcome VARCHAR(32) NOT NULL
);

CREATE INDEX idx_webhook_delivery_attempts_event_id ON webhook_delivery_attempts (event_id, attempted_at);

CREATE TABLE finalized_conformance_results (
    run_id UUID PRIMARY KEY REFERENCES test_runs(run_id) ON DELETE CASCADE,
    verdict VARCHAR(32) NOT NULL,
    finalized_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE finalized_conformance_assertions (
    run_id UUID NOT NULL REFERENCES test_runs(run_id) ON DELETE CASCADE,
    ordinal INT NOT NULL,
    assertion_id VARCHAR(64) NOT NULL,
    invariant_id VARCHAR(32) NOT NULL,
    verdict VARCHAR(32) NOT NULL,
    explanation TEXT NOT NULL,
    evidence_event_ids BIGINT[] NOT NULL,
    PRIMARY KEY (run_id, ordinal)
);

