package com.stockbroker.trading.job;

import com.stockbroker.trading.domain.Account;
import com.stockbroker.trading.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Reconciliation job - periodically rebuilds balances from ledger and compares with derived caches.
 * Runs every 6 hours by default (configurable).
 */
@Component
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    private final LedgerService ledgerService;
    private final JdbcTemplate jdbcTemplate;

    public ReconciliationJob(LedgerService ledgerService, 
                             JdbcTemplate jdbcTemplate) {
        this.ledgerService = ledgerService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs full reconciliation every 6 hours.
     * Can be disabled via property: sb.reconciliation.enabled=false
     */
    @Scheduled(cron = "0 0 */6 * * *") // Every 6 hours at minute 0
    public void runReconciliation() {
        log.info("Starting scheduled reconciliation job at {}", Instant.now());
        
        // Record start of reconciliation run
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO reconciliation_runs (id, started_at, status, accounts_checked, discrepancies_found, created_at) VALUES (?, ?, 'RUNNING', 0, 0, ?)",
            runId, Instant.now(), Instant.now()
        );
        
        try {
            var result = ledgerService.runFullReconciliation();
            
            log.info("Reconciliation completed: {} accounts checked, {} discrepancies found",
                    result.accountsChecked(), result.discrepanciesFound());
            
            // Record discrepancies if any
            for (var d : result.discrepancies()) {
                jdbcTemplate.update(
                    "INSERT INTO reconciliation_discrepancies (run_id, asset, ledger_balance, cache_balance, note, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    runId, d.asset(), d.ledgerBalance(), d.cacheBalance(), d.note(), Instant.now()
                );
            }
            
            // Update run record
            jdbcTemplate.update(
                "UPDATE reconciliation_runs SET finished_at = ?, status = ?, accounts_checked = ?, discrepancies_found = ? WHERE id = ?",
                Instant.now(), "COMPLETED", result.accountsChecked(), result.discrepanciesFound(), runId
            );
            
            if (result.discrepanciesFound() > 0) {
                log.warn("RECONCILIATION DISCREPANCIES DETECTED: {}", result.discrepancies());
                for (var d : result.discrepancies()) {
                    log.warn("  Asset: {}, Ledger: {}, Cache: {}, Note: {}", 
                            d.asset(), d.ledgerBalance(), d.cacheBalance(), d.note());
                }
            } else {
                log.info("Reconciliation clean - all balances match");
            }
        } catch (Exception e) {
            log.error("Reconciliation job failed", e);
            jdbcTemplate.update(
                "UPDATE reconciliation_runs SET finished_at = ?, status = ?, error_message = ? WHERE id = ?",
                Instant.now(), "FAILED", e.getMessage(), runId
            );
        }
    }

    /**
     * Run reconciliation for a specific account (on-demand).
     */
    public LedgerService.ReconciliationResult runForAccount(Account account) {
        var discrepancies = ledgerService.reconcile(account.getId());
        return new LedgerService.ReconciliationResult(1, discrepancies.size(), discrepancies);
    }
}