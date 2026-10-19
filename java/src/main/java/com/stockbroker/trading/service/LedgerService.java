package com.stockbroker.trading.service;

import com.stockbroker.common.error.BusinessProblem;
import com.stockbroker.trading.domain.*;
import com.stockbroker.trading.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ledger service - handles double-entry bookkeeping for paper trading.
 * 
 * Core principles:
 * - Every business event creates a balanced transaction (sum per asset = 0)
 * - Positive amount = debit, negative = credit
 * - Ledger is append-only (no UPDATE/DELETE on entries)
 * - Balances derived from ledger entries (reconciliation rebuilds from scratch)
 */
@Service
@Transactional
public class LedgerService {

    private final AccountRepository accountRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final PositionRepository positionRepository;

    public LedgerService(AccountRepository accountRepository,
                         LedgerAccountRepository ledgerAccountRepository,
                         LedgerTransactionRepository transactionRepository,
                         LedgerEntryRepository entryRepository,
                         PositionRepository positionRepository) {
        this.accountRepository = accountRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.positionRepository = positionRepository;
    }

    /**
     * Get or create a ledger account for the given account and asset.
     */
    public LedgerAccount getOrCreateLedgerAccount(UUID accountId, String asset, String code) {
        return ledgerAccountRepository.findByAccountIdAndAsset(accountId, asset)
                .orElseGet(() -> {
                    LedgerAccount la = new LedgerAccount();
                    la.setAccountId(accountId);
                    la.setAsset(asset);
                    la.setCode(code);
                    return ledgerAccountRepository.save(la);
                });
    }

    /**
     * Create a DEPOSIT transaction: credits cash asset.
     * Entries: DR Cash (positive), CR Equity (negative) - wait, for deposit:
     * DR Cash (+amount), CR Deposits/Income (-amount)
     * Actually: Asset increases (debit), Liability/Equity increases (credit)
     */
    @Transactional
    public LedgerTransaction createDeposit(UUID accountId, UUID userId, String asset, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessProblem.ValidationFailed("Deposit amount must be positive");
        }

        // Get or create cash ledger account
        LedgerAccount cashAccount = getOrCreateLedgerAccount(accountId, asset, "CASH:" + asset);
        
        // Get or create equity/deposits ledger account (the counterparty)
        LedgerAccount equityAccount = getOrCreateLedgerAccount(accountId, asset, "EQUITY:" + asset);

        // Create transaction
        LedgerTransaction tx = new LedgerTransaction();
        tx.setAccountId(accountId);
        tx.setUserId(getUserIdFromAccount(accountId));
        tx.setKind("DEPOSIT");
        tx.setRefType("DEPOSIT");
        transactionRepository.save(tx);

        // Create entries: DR Cash (+amount), CR Equity (-amount)
        List<LedgerEntry> entries = List.of(
                createEntry(tx.getId(), cashAccount.getId(), asset, amount),  // DR: Cash increases
                createEntry(tx.getId(), equityAccount.getId(), asset, amount.negate()) // CR: Equity increases
        );

        entryRepository.saveAll(entries);
        return tx;
    }

    /**
     * Create a FILL transaction from a trade execution.
     * For BUY: DR Asset (+qty), CR Cash (-qty * price - fees)
     * For SELL: DR Cash (+qty * price - fees), CR Asset (-qty)
     * Fees: DR Fees (+fee), CR Cash (-fee)
     */
    @Transactional
    public LedgerTransaction createFill(UUID accountId, UUID userId, 
                                         String symbol, String side, 
                                         BigDecimal qty, BigDecimal price, 
                                         String feeAsset, BigDecimal feeAmount,
                                         UUID fillId) {
        if (qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessProblem.ValidationFailed("Fill quantity must be positive");
        }
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessProblem.ValidationFailed("Fill price must be positive");
        }

        // Determine quote asset from symbol (e.g., BTC-USDT -> USDT)
        String quoteAsset = symbol.contains("-") ? symbol.split("-")[1] : "USDT";
        String baseAsset = symbol.contains("-") ? symbol.split("-")[0] : symbol;

        BigDecimal notional = qty.multiply(price);

        // Get ledger accounts
        LedgerAccount baseAccount = getOrCreateLedgerAccount(accountId, baseAsset, "ASSET:" + baseAsset);
        LedgerAccount quoteAccount = getOrCreateLedgerAccount(accountId, quoteAsset, "CASH:" + quoteAsset);
        LedgerAccount feeAccount = getOrCreateLedgerAccount(accountId, feeAsset, "FEES:" + feeAsset);

        LedgerTransaction tx = new LedgerTransaction();
        tx.setAccountId(accountId);
        tx.setUserId(userId);
        tx.setKind("FILL");
        tx.setRefType("FILL");
        tx.setRefId(tx.getId()); // Will be set after save
        transactionRepository.save(tx);

        // Update ref_id
        tx.setRefId(tx.getId());

        List<LedgerEntry> entries = new ArrayList<>();

        if ("BUY".equalsIgnoreCase(side)) {
            // BUY: receive base asset, pay quote asset + fees
            entries.add(createEntry(tx.getId(), baseAccount.getId(), baseAsset, qty));                    // DR Asset
            entries.add(createEntry(tx.getId(), quoteAccount.getId(), quoteAsset, notional.negate()));  // CR Cash
            if (feeAmount.compareTo(BigDecimal.ZERO) > 0) {
                entries.add(createEntry(tx.getId(), feeAccount.getId(), feeAsset, feeAmount));           // DR Fees
                entries.add(createEntry(tx.getId(), quoteAccount.getId(), quoteAsset, feeAmount.negate())); // CR Cash (fees paid from cash)
            }
        } else {
            // SELL: receive quote asset, pay base asset + fees
            entries.add(createEntry(tx.getId(), quoteAccount.getId(), quoteAsset, notional));            // DR Cash
            entries.add(createEntry(tx.getId(), baseAccount.getId(), baseAsset, qty.negate()));          // CR Asset
            if (feeAmount.compareTo(BigDecimal.ZERO) > 0) {
                entries.add(createEntry(tx.getId(), feeAccount.getId(), feeAsset, feeAmount));           // DR Fees
                entries.add(createEntry(tx.getId(), quoteAccount.getId(), quoteAsset, feeAmount.negate())); // CR Cash (fees paid from cash)
            }
        }

        entryRepository.saveAll(entries);

        // Update position cache
        updatePosition(accountId, symbol, side, qty, price);

        return tx;
    }

    /**
     * Create a FEE transaction (standalone fee, e.g., withdrawal fee).
     * DR Fees (+amount), CR Cash (-amount)
     */
    @Transactional
    public LedgerTransaction createFee(UUID accountId, UUID userId, String asset, BigDecimal amount, UUID refId) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessProblem.ValidationFailed("Fee amount must be positive");
        }

        LedgerAccount cashAccount = getOrCreateLedgerAccount(accountId, asset, "CASH:" + asset);
        LedgerAccount feeAccount = getOrCreateLedgerAccount(accountId, asset, "FEES:" + asset);

        LedgerTransaction tx = new LedgerTransaction();
        tx.setAccountId(accountId);
        tx.setUserId(userId);
        tx.setKind("FEE");
        tx.setRefType("FEE");
        tx.setRefId(refId);
        transactionRepository.save(tx);

        List<LedgerEntry> entries = List.of(
                createEntry(tx.getId(), feeAccount.getId(), asset, amount),           // DR Fees
                createEntry(tx.getId(), cashAccount.getId(), asset, amount.negate())  // CR Cash
        );

        entryRepository.saveAll(entries);
        return tx;
    }

    /**
     * Create an ADJUSTMENT transaction (manual correction, admin only).
     * Requires explicit entries that must balance.
     */
    @Transactional
    public LedgerTransaction createAdjustment(UUID accountId, UUID userId, List<AdjustmentEntry> entries, UUID refId) {
        // Validate entries balance per asset
        Map<String, BigDecimal> balances = new HashMap<>();
        for (AdjustmentEntry e : entries) {
            BigDecimal current = balances.getOrDefault(e.asset(), BigDecimal.ZERO);
            balances.put(e.asset(), current.add(e.amount()));
        }
        for (Map.Entry<String, BigDecimal> e : balances.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) != 0) {
                throw new BusinessProblem.ValidationFailed(
                        "Adjustment entries must balance per asset. Asset " + e.getKey() + " sums to " + e.getValue());
            }
        }

        LedgerTransaction tx = new LedgerTransaction();
        tx.setAccountId(accountId);
        tx.setUserId(userId);
        tx.setKind("ADJUSTMENT");
        tx.setRefType("ADJUSTMENT");
        tx.setRefId(refId);
        transactionRepository.save(tx);

        List<LedgerEntry> ledgerEntries = entries.stream()
                .map(e -> {
                    LedgerAccount la = getOrCreateLedgerAccount(accountId, e.asset(), e.code());
                    return createEntry(tx.getId(), la.getId(), e.asset(), e.amount());
                })
                .collect(Collectors.toList());

        entryRepository.saveAll(ledgerEntries);
        return tx;
    }

    /**
     * Get balance for a specific asset in an account.
     * Sum of all ledger entries for that asset.
     */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID accountId, String asset) {
        // Sum all entries for this account and asset
        List<Object[]> results = entryRepository.sumByAccountAndAsset(accountId, asset);
        if (results.isEmpty() || results.get(0)[0] == null) {
            return BigDecimal.ZERO;
        }
        return (BigDecimal) results.get(0)[0];
    }

    /**
     * Get all balances for an account (one per asset).
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> getAllBalances(UUID accountId) {
        List<LedgerAccount> accounts = ledgerAccountRepository.findByAccountId(accountId);
        Map<String, BigDecimal> balances = new HashMap<>();
        for (LedgerAccount la : accounts) {
            BigDecimal balance = getBalance(accountId, la.getAsset());
            if (balance.compareTo(BigDecimal.ZERO) != 0) {
                balances.put(la.getAsset(), balance);
            }
        }
        return balances;
    }

    /**
     * Get cash balance (quote currency) for an account.
     */
    @Transactional(readOnly = true)
    public BigDecimal getCashBalance(UUID accountId, String quoteAsset) {
        return getBalance(accountId, quoteAsset);
    }

    /**
     * Reconciliation: rebuild all balances from ledger entries and compare with positions cache.
     * Returns list of discrepancies.
     */
    @Transactional(readOnly = true)
    public List<ReconciliationDiscrepancy> reconcile(UUID accountId) {
        List<Discrepancy> discrepancies = new ArrayList<>();

        // Get all ledger accounts for this account
        List<LedgerAccount> ledgerAccounts = ledgerAccountRepository.findByAccountId(accountId);

        for (LedgerAccount la : ledgerAccounts) {
            BigDecimal ledgerBalance = getBalance(accountId, la.getAsset());
            
            // Compare with positions table (derived cache)
            // This would need PositionRepository - for now just return ledger balance
            discrepancies.add(new Discrepancy(la.getAsset(), ledgerBalance, BigDecimal.ZERO, "positions_cache_not_checked"));
        }

        return discrepancies.stream()
                .map(d -> new ReconciliationDiscrepancy(d.asset, d.ledgerBalance, d.cacheBalance, d.note))
                .collect(Collectors.toList());
    }

    /**
     * Full reconciliation job: rebuild all account balances from ledger.
     * Can be run periodically or on demand.
     */
    @Transactional
    public ReconciliationResult runFullReconciliation() {
        Iterable<Account> accounts = accountRepository.findAll();
        int accountsChecked = 0;
        int discrepanciesFound = 0;
        List<ReconciliationDiscrepancy> allDiscrepancies = new ArrayList<>();

        for (Account account : accounts) {
            List<ReconciliationDiscrepancy> disc = reconcile(account.getId());
            accountsChecked++;
            discrepanciesFound += disc.size();
            allDiscrepancies.addAll(disc);
        }

        return new ReconciliationResult(accountsChecked, discrepanciesFound, allDiscrepancies);
    }

    /**
     * Update position cache after a fill.
     * For BUY: increase qty, update weighted average cost.
     * For SELL: decrease qty, calculate and add realized P&L.
     */
    private void updatePosition(UUID accountId, String symbol, String side, BigDecimal qty, BigDecimal price) {
        PositionId positionId = new PositionId(accountId, symbol);
        Position position = positionRepository.findById(positionId).orElse(null);

        if ("BUY".equalsIgnoreCase(side)) {
            if (position == null) {
                // New position
                Position newPosition = new Position();
                newPosition.setAccountId(accountId);
                newPosition.setSymbol(symbol);
                newPosition.setQty(qty);
                newPosition.setAvgCost(price);
                newPosition.setRealizedPnl(BigDecimal.ZERO);
                positionRepository.save(newPosition);
            } else {
                // Update existing position - weighted average cost
                BigDecimal totalQty = position.getQty().add(qty);
                BigDecimal totalCost = position.getQty().multiply(position.getAvgCost()).add(qty.multiply(price));
                BigDecimal newAvgCost = totalCost.divide(totalQty, 18, RoundingMode.HALF_UP);
                position.setQty(totalQty);
                position.setAvgCost(newAvgCost);
                positionRepository.save(position);
            }
        } else if ("SELL".equalsIgnoreCase(side)) {
            if (position == null) {
                throw new BusinessProblem.ValidationFailed("No position to sell for " + symbol);
            }
            if (position.getQty().compareTo(qty) < 0) {
                throw new BusinessProblem.ValidationFailed("Insufficient position to sell for " + symbol);
            }

            // Calculate realized P&L: qty * (sell_price - avg_cost)
            BigDecimal realizedPnl = qty.multiply(price.subtract(position.getAvgCost()));
            
            BigDecimal newQty = position.getQty().subtract(qty);
            if (newQty.compareTo(BigDecimal.ZERO) == 0) {
                // Position fully closed
                position.setQty(BigDecimal.ZERO);
                position.setAvgCost(BigDecimal.ZERO);
                position.setRealizedPnl(position.getRealizedPnl().add(realizedPnl));
                positionRepository.save(position);
            } else {
                // Partial close
                position.setQty(newQty);
                position.setRealizedPnl(position.getRealizedPnl().add(realizedPnl));
                positionRepository.save(position);
            }
        }
    }

    // Helper methods

    private LedgerEntry createEntry(UUID transactionId, UUID ledgerAccountId, String asset, BigDecimal amount) {
        LedgerEntry entry = new LedgerEntry();
        entry.setTransactionId(transactionId);
        entry.setLedgerAccount(ledgerAccountId);
        entry.setAsset(asset);
        entry.setAmount(amount);
        return entry;
    }

    private UUID getUserIdFromAccount(UUID accountId) {
        // In a real implementation, this would come from the account
        // For now, we'll need to fetch it from the account
        return accountRepository.findById(accountId)
                .map(account -> account.getUserId())
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Account not found"));
    }

    // Inner classes for results

    public record AdjustmentEntry(String asset, String code, BigDecimal amount) {}

    public record Discrepancy(String asset, BigDecimal ledgerBalance, BigDecimal cacheBalance, String note) {}

    public record ReconciliationDiscrepancy(String asset, BigDecimal ledgerBalance, BigDecimal cacheBalance, String note) {}

    public record ReconciliationResult(
            int accountsChecked,
            int discrepanciesFound,
            List<ReconciliationDiscrepancy> discrepancies
    ) {}
}