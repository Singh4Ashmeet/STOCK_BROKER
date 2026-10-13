-- V003__reconciliation_audit.sql
-- Table to track reconciliation runs and their results

CREATE TABLE reconciliation_runs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      uuid REFERENCES accounts(id),
    started_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    status          text NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    accounts_checked int NOT NULL DEFAULT 0,
    discrepancies_found int NOT NULL DEFAULT 0,
    error_message   text,
    details         jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_reconciliation_runs_started_at ON reconciliation_runs (started_at DESC);
CREATE INDEX idx_reconciliation_runs_account_id ON reconciliation_runs (account_id);

-- Table to track individual discrepancies found during reconciliation
CREATE TABLE reconciliation_discrepancies (
    id              bigserial PRIMARY KEY,
    run_id          uuid NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    asset           text NOT NULL,
    ledger_balance  numeric(38,18) NOT NULL,
    cache_balance   numeric(38,18) NOT NULL,
    discrepancy     numeric(38,18) GENERATED ALWAYS AS (ledger_balance - cache_balance) STORED,
    note            text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_reconciliation_discrepancies_run_id ON reconciliation_discrepancies (run_id);