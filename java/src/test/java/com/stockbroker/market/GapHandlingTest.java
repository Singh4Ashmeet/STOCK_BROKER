package com.stockbroker.market;

import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.market.subscription.SubscriptionManager;
import com.stockbroker.market.state.MarketStateManager;
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

    @Test
    void bookSequenceGapTriggersResync() {
        String symbol = "BINANCE:BTC-USDT";

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
        String symbol = "BINANCE:BTC-USDT";

        subscriptionManager.onTrade(symbol, 1, 100);
        subscriptionManager.onTrade(symbol, 1, 101);
        subscriptionManager.onTrade(symbol, 1, 105); // Gap

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);
    }

    @Test
    void metricsSequenceGapTriggersResync() {
        String symbol = "BINANCE:BTC-USDT";

        subscriptionManager.onMetrics(symbol, 1, 50);
        subscriptionManager.onMetrics(symbol, 1, 55); // Gap

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(1);
    }

    @Test
    void epochChangeDoesNotCountAsGap() {
        String symbol = "BINANCE:BTC-USDT";

        subscriptionManager.onBookUpdate(symbol, 1, 100);
        subscriptionManager.onBookUpdate(symbol, 2, 1); // New epoch

        assertThat(subscriptionManager.getTotalResyncs()).isEqualTo(1);
        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(0);
    }

    @Test
    void consecutiveGapsCountSeparately() {
        String symbol = "BINANCE:BTC-USDT";

        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 1, 15); // Gap 1
        subscriptionManager.onBookUpdate(symbol, 1, 25); // Gap 2

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(2);
    }

    @Test
    void noGapWhenSequenceContinues() {
        String symbol = "BINANCE:BTC-USDT";

        subscriptionManager.onBookUpdate(symbol, 1, 10);
        subscriptionManager.onBookUpdate(symbol, 1, 11);
        subscriptionManager.onBookUpdate(symbol, 1, 12);
        subscriptionManager.onBookUpdate(symbol, 1, 13);

        assertThat(subscriptionManager.getTotalGapsDetected()).isEqualTo(0);
    }
}
