package com.stockbroker.market;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.market.subscription.SubscriptionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SubscriptionManager - refcounted subscriptions, gap detection, and resync.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SubscriptionManagerTest {

    @Autowired
    SubscriptionManager subscriptionManager;

    @BeforeEach
    void resetCounters() {
        try {
            var gapsField = SubscriptionManager.class.getDeclaredField("totalGapsDetected");
            gapsField.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) gapsField.get(subscriptionManager)).set(0);
            var resyncsField = SubscriptionManager.class.getDeclaredField("totalResyncs");
            resyncsField.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) resyncsField.get(subscriptionManager)).set(0);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset SubscriptionManager counters", e);
        }
    }

    @Test
    void subscriptionManagerTracksRefCounts() {
        // Initially no subscriptions
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(0);
        assertThat(subscriptionManager.getActiveTradesSubscriptions()).isEqualTo(0);
        assertThat(subscriptionManager.getActiveMetricsSubscriptions()).isEqualTo(0);

        // Add subscribers
        subscriptionManager.addBookSubscriber("BINANCE:BTC-USDT");
        subscriptionManager.addBookSubscriber("BINANCE:BTC-USDT"); // Second subscriber same symbol
        subscriptionManager.addBookSubscriber("BINANCE:ETH-USDT");

        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(2);

        // Remove one subscriber
        subscriptionManager.removeBookSubscriber("BINANCE:BTC-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(2); // Still 1 ref on BTC, 1 on ETH

        // Remove last BTC subscriber
        subscriptionManager.removeBookSubscriber("BINANCE:BTC-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(1); // Only ETH remains

        // Remove ETH
        subscriptionManager.removeBookSubscriber("BINANCE:ETH-USDT");
        assertThat(subscriptionManager.getActiveBookSubscriptions()).isEqualTo(0);
    }

    @Test
    void gapDetectionIncrementsCounters() {
        // Simulate sequence gap detection - use unique symbol to avoid mock feed interference
        String symbol = "SUB_TEST:BTC-USDT";
        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 1, 15); // Gap of 4

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);

        // Another gap - jump from 15 to 25 (skip 20 to avoid intermediate gap)
        subscriptionManager.onBookUpdate(symbol, 1, 25); // Gap of 9 (from 15 to 25)

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(2);
    }

    @Test
    void epochChangeCountsAsResyncNotGap() {
        String symbol = "SUB_TEST:BTC-USDT";
        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 2, 1); // New epoch, seq resets

        // Should count as resync, not gap
        assertThat(subscriptionManager.getTotalResyncs()).isEqualTo(1);
        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(0);
    }

    @Test
    void sequenceTrackingWorks() {
        String symbol = "SUB_TEST:BTC-USDT";
        subscriptionManager.onTrade(symbol, 1, 100);
        subscriptionManager.onTrade(symbol, 1, 101);
        subscriptionManager.onTrade(symbol, 1, 103); // Gap

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);
    }
}
