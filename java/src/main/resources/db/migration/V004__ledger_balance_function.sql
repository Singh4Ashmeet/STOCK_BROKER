-- V004__ledger_balance_function.sql
-- Database function to efficiently compute account balance per asset

CREATE OR REPLACE FUNCTION get_account_balance(p_account_id uuid, p_asset text)
RETURNS numeric AS $$
DECLARE
    v_balance numeric;
BEGIN
    SELECT COALESCE(SUM(le.amount), 0) INTO v_balance
    FROM ledger_entries le
    JOIN ledger_transactions lt ON le.transaction_id = lt.id
    WHERE lt.account_id = p_account_id
      AND le.asset = p_asset;
    
    RETURN v_balance;
END;
$$ LANGUAGE plpgsql STABLE;

-- Function to get all balances for an account
CREATE OR REPLACE FUNCTION get_account_balances(p_account_id uuid)
RETURNS TABLE(asset text, balance numeric) AS $$
BEGIN
    RETURN QUERY
    SELECT le.asset, COALESCE(SUM(le.amount), 0) as balance
    FROM ledger_entries le
    JOIN ledger_transactions lt ON le.transaction_id = lt.id
    WHERE lt.account_id = p_account_id
    GROUP BY le.asset
    HAVING COALESCE(SUM(le.amount), 0) <> 0;
END;
$$ LANGUAGE plpgsql STABLE;

-- Function to verify all transactions balance (for reconciliation)
CREATE OR REPLACE FUNCTION verify_ledger_balances()
RETURNS TABLE(transaction_id uuid, asset text, imbalance numeric) AS $$
BEGIN
    RETURN QUERY
    SELECT le.transaction_id, le.asset, SUM(le.amount) as imbalance
    FROM ledger_entries le
    GROUP BY le.transaction_id, le.asset
    HAVING SUM(le.amount) <> 0;
END;
$$ LANGUAGE plpgsql STABLE;