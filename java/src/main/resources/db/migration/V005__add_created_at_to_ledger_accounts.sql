-- V005__add_created_at_to_ledger_accounts.sql
-- Add created_at column to ledger_accounts table

ALTER TABLE ledger_accounts ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now();
