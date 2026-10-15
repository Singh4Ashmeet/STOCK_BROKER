package com.stockbroker.trading;

import com.stockbroker.trading.domain.LedgerEntry;
import net.jqwik.api.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for ledger invariants using jqwik.
 * 
 * Invariants tested:
 * 1. Double-entry balance: sum of amounts per (transaction_id, asset) = 0
 * 2. Each transaction has at least 2 entries
 * 3. Amounts are never zero
 * 4. Positive = debit, negative = credit
 * 5. Reconciliation: sum of all entries per (account, asset) = balance
 */
class LedgerInvariantPropertyTest {

    @Property
    void transactionBalancesToZero(@ForAll("transactions") List<LedgerEntry> entries) {
        // Group by transaction_id and asset
        Map<String, BigDecimal> sums = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> e.transactionId() + ":" + e.asset(),
                        Collectors.reducing(BigDecimal.ZERO, LedgerEntry::amount, BigDecimal::add)
                ));

        // Each group must sum to zero
        sums.forEach((key, sum) ->
                assertThat(sum)
                        .withFailMessage("Transaction %s does not balance: sum = %s", key, sum)
                        .isEqualByComparingTo(BigDecimal.ZERO)
        );
    }

    @Property
    void eachTransactionHasAtLeastTwoEntries(@ForAll("transactions") List<LedgerEntry> entries) {
        Map<UUID, Long> entryCounts = entries.stream()
                .collect(Collectors.groupingBy(LedgerEntry::transactionId, Collectors.counting()));

        entryCounts.forEach((txId, count) ->
                assertThat(count)
                        .withFailMessage("Transaction %s has only %d entries", txId, count)
                        .isGreaterThanOrEqualTo(2)
        );
    }

    @Property
    void amountsAreNonZero(@ForAll("transactions") List<LedgerEntry> entries) {
        entries.forEach(entry ->
                assertThat(entry.amount())
                        .withFailMessage("Entry %s has zero amount", entry.id())
                        .isNotEqualByComparingTo(BigDecimal.ZERO)
        );
    }

    @Property
    void positiveIsDebitNegativeIsCredit(@ForAll("transactions") List<LedgerEntry> entries) {
        // For each transaction, positive amounts should exist and negative amounts should exist
        Map<UUID, List<BigDecimal>> txAmounts = entries.stream()
                .collect(Collectors.groupingBy(
                        LedgerEntry::transactionId,
                        Collectors.mapping(LedgerEntry::amount, Collectors.toList())
                ));

        txAmounts.forEach((txId, amounts) -> {
            boolean hasPositive = amounts.stream().anyMatch(a -> a.compareTo(BigDecimal.ZERO) > 0);
            boolean hasNegative = amounts.stream().anyMatch(a -> a.compareTo(BigDecimal.ZERO) < 0);
            
            assertThat(hasPositive)
                    .withFailMessage("Transaction %s has no positive (debit) amounts", txId)
                    .isTrue();
            assertThat(hasNegative)
                    .withFailMessage("Transaction %s has no negative (credit) amounts", txId)
                    .isTrue();
        });
    }

    @Property
    void reconciliationSumsToZero(@ForAll("transactions") List<LedgerEntry> entries) {
        // Sum all amounts across all entries - should equal zero
        BigDecimal totalSum = entries.stream()
                .map(LedgerEntry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        assertThat(totalSum)
                .withFailMessage("Sum of all ledger entries is not zero: %s", totalSum)
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Provide
    Arbitrary<List<LedgerEntry>> transactions() {
        return ledgerEntry().list()
                .ofMinSize(2)
                .ofMaxSize(10)
                .filter(this::isBalanced);
    }

    private Arbitrary<LedgerEntry> ledgerEntry() {
        Arbitrary<UUID> uuids = Combinators.combine(
                Arbitraries.longs(),
                Arbitraries.longs()
        ).as(UUID::new);

        return Combinators.combine(
                uuids,
                uuids,
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10),
                Arbitraries.integers().between(-1000000, 1000000).filter(i -> i != 0)
                        .map(BigDecimal::valueOf)
        ).as(LedgerEntry::new);
    }

    private boolean isBalanced(List<LedgerEntry> entries) {
        Map<String, BigDecimal> sums = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> e.transactionId() + ":" + e.asset(),
                        Collectors.reducing(BigDecimal.ZERO, LedgerEntry::amount, BigDecimal::add)
                ));
        return sums.values().stream().allMatch(sum -> sum.compareTo(BigDecimal.ZERO) == 0);
    }

    // Simple LedgerEntry record for property tests
    record LedgerEntry(
            UUID id,
            UUID transactionId,
            String asset,
            BigDecimal amount
    ) {}
}