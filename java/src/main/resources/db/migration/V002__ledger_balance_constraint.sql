-- V002__ledger_balance_constraint.sql
-- Deferred constraint trigger to enforce double-entry balance per transaction/asset
-- See docs/05-DATA-MODEL.md section 1.2

-- Function to check balance for a transaction
CREATE OR REPLACE FUNCTION check_ledger_balance()
RETURNS TRIGGER AS $$
DECLARE
    v_sum numeric;
BEGIN
    -- Check balance for the transaction being inserted/updated
    SELECT COALESCE(SUM(amount), 0) INTO v_sum
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id
      AND asset = NEW.asset;

    IF v_sum <> 0 THEN
        RAISE EXCEPTION 'Ledger balance violation: transaction % asset % sums to %, expected 0',
            NEW.transaction_id, NEW.asset, v_sum;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger on ledger_entries - deferred to end of transaction
CREATE TRIGGER enforce_ledger_balance
    AFTER INSERT OR UPDATE ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_ledger_balance();

-- Also add a constraint to ensure each transaction has at least 2 entries
CREATE OR REPLACE FUNCTION check_transaction_entry_count()
RETURNS TRIGGER AS $$
DECLARE
    v_count int;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM ledger_entries
    WHERE transaction_id = NEW.transaction_id;

    IF v_count < 2 THEN
        RAISE EXCEPTION 'Transaction % must have at least 2 entries (double-entry)', NEW.transaction_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER enforce_entry_count
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_entry_count();

-- Index for reconciliation queries
CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_asset 
    ON ledger_entries (ledger_account, asset);

CREATE INDEX IF NOT EXISTS idx_ledger_transactions_account_created 
    ON ledger_transactions (account_id, created_at DESC);