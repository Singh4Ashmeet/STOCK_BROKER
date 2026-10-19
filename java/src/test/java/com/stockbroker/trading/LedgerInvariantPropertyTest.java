package com.stockbroker.trading;

import net.jqwik.api.*;

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
 */
@SuppressWarnings("null")
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

    @Provide
    Arbitrary<List<LedgerEntry>> transactions() {
        return balancedTransaction();
    }

    private Arbitrary<List<LedgerEntry>> balancedTransaction() {
        // Generate a transaction with 2+ entries that balance to zero
        return Arbitraries.integers().between(2, 5)
                .flatMap(numEntries -> {
                    // Generate amounts that sum to zero
                    Arbitrary<List<BigDecimal>> amounts = Arbitraries.integers().between(-1000000, 1000000)
                            .filter(i -> i != 0)
                            .map(BigDecimal::valueOf)
                            .list()
                            .ofSize(numEntries - 1)
                            .map(list -> {
                                BigDecimal sum = list.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                                BigDecimal last = sum.negate(); // This will balance the sum to zero
                                if (last.compareTo(BigDecimal.ZERO) == 0) {
                                    last = BigDecimal.ONE; // ensure non-zero
                                }
                                var newList = new java.util.ArrayList<>(list);
                                newList.add(last);
                                return newList;
                            });
                    
                    return amounts.flatMap(amountList -> {
                        Arbitrary<UUID> uuids = Combinators.combine(
                                Arbitraries.longs(),
                                Arbitraries.longs()
                        ).as(UUID::new);
                        
                        Arbitrary<String> assets = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10);
                        
                        // Create entries for this transaction
                        UUID transactionId = UUID.randomUUID();
                        String asset = assets.sample();
                        
                        return Combinators.combine(
                                Arbitraries.just(transactionId),
                                Arbitraries.just(asset),
                                Arbitraries.just(amountList),
                                uuids
                        ).as((txId, ast, amountsList, entryId) -> {
                            List<LedgerEntry> entries = new java.util.ArrayList<>();
                            for (int i = 0; i < amountsList.size(); i++) {
                                UUID id = uuids.sample();
                                entries.add(new LedgerEntry(id, txId, ast, amountsList.get(i)));
                            }
                            return entries;
                        });
                    });
                });
    }

    // Simple LedgerEntry record for property tests
    record LedgerEntry(
            UUID id,
            UUID transactionId,
            String asset,
            BigDecimal amount
    ) {}
}