package com.stockbroker.market;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.market.subscription.SubscriptionManager;
import com.stockbroker.market.state.MarketStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for gap detection and automatic resync via GetSnapshot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
class GapHandlingTest {

    @Autowired
    SubscriptionManager subscriptionManager;

    @Autowired
    MarketStateManager stateManager;

    @BeforeEach
    void resetCounters() {
        // Reset gap and resync counters to isolate tests
        // Using reflection since fields are private
        try {
            var gapsField = SubscriptionManager.class.getDeclaredField("totalGapsDetected");
            gapsField.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) gapsField.get(subscriptionManager)).set(0);
            var resyncsField = SubscriptionManager.class.getDeclaredField("totalResyncs");
            resyncsField.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicLong) resyncsField.get(subscriptionManager)).set(0);
            // Also clear sequence tracking maps
            var bookSeqField = SubscriptionManager.class.getDeclaredField("lastBookSeq");
            bookSeqField.setAccessible(true);
            ((java.util.concurrent.ConcurrentHashMap<?, ?>) bookSeqField.get(subscriptionManager)).clear();
            var tradesSeqField = SubscriptionManager.class.getDeclaredField("lastTradesSeq");
            tradesSeqField.setAccessible(true);
            ((java.util.concurrent.ConcurrentHashMap<?, ?>) tradesSeqField.get(subscriptionManager)).clear();
            var metricsSeqField = SubscriptionManager.class.getDeclaredField("lastMetricsSeq");
            metricsSeqField.setAccessible(true);
            ((java.util.concurrent.ConcurrentHashMap<?, ?>) metricsSeqField.get(subscriptionManager)).clear();
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset SubscriptionManager counters", e);
        }
    }

    @Test
    void bookSequenceGapTriggersResync() {
        String symbol = "GAP_TEST:BTC-USDT";

        // Simulate normal sequence
        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 1, 11);
        subscriptionManager.onBookUpdate(symbol, 1, 12);

        // Simulate gap (seq jumps from 12 to 20)
        subscriptionManager.onBookUpdate(symbol, 1, 20);

        // Gap should be detected
        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);

        // Resync should be triggered asynchronously
        // We can't easily test the async resync without mocking the gRPC client,
        // but we can verify the gap counter increments
    }

    @Test
    void tradeSequenceGapTriggersResync() {
        String symbol = "GAP_TEST:BTC-USDT";

        subscriptionManager.onTrade(symbol, 1, 100);
        subscriptionManager.onTrade(symbol, 1, 101);
        subscriptionManager.onTrade(symbol, 1, 105); // Gap

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);
    }

    @Test
    void metricsSequenceGapTriggersResync() {
        String symbol = "GAP_TEST:BTC-USDT";

        subscriptionManager.onMetrics(symbol, 1, 50);
        subscriptionManager.onMetrics(symbol, 1, 55); // Gap

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);
    }

    @Test
    void epochChangeDoesNotCountAsGap() {
        String symbol = "GAP_TEST:BTC-USDT";

        subscriptionManager.onBookUpdate(symbol, 1, 100);
        subscriptionManager.onBookUpdate(symbol, 2, 1); // New epoch

        assertThat(subscriptionManager.getTotalResyncs()).isEqualTo(1);
        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(0);
    }

    @Test
    void consecutiveGapsCountSeparately() {
        String symbol = "GAP_TEST:BTC-USDT";

        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 1, 15); // Gap 1
        subscriptionManager.onBookUpdate(symbol, 1, 25); // Gap 2

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(2);
    }

    @Test
    void noGapWhenSequenceContinues() {
        String symbol = "GAP_TEST:BTC-USDT";

        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 1, 11);
        subscriptionManager.onBookUpdate(symbol, 1, 12);
        subscriptionManager.onBookUpdate(symbol, 1, 13);

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(0);
    }
}
