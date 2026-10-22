-- V007__fix_entry_count_trigger.sql
-- Fix check_transaction_entry_count function to reference NEW.id instead of NEW.transaction_id
-- The trigger enforce_entry_count runs on ledger_transactions, where the primary key column is id.

CREATE OR REPLACE FUNCTION check_transaction_entry_count()
RETURNS TRIGGER AS $$
DECLARE
    v_count int;
BEGIN
    SELECT COUNT(*) INTO v_count
    FROM ledger_entries
    WHERE transaction_id = NEW.id;

    IF v_count < 2 THEN
        RAISE EXCEPTION 'Transaction % must have at least 2 entries (double-entry)', NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
