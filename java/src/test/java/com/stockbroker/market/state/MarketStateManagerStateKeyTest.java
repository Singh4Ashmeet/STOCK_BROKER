package com.stockbroker.market.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketStateManagerStateKeyTest {

    @Test
    void extractsSymbolsOnlyFromWellFormedMirrorKeys() {
        assertThat(MarketStateManager.symbolFromStateKey("mkt:BINANCE:BTC-USDT:book", ":book"))
                .contains("BINANCE:BTC-USDT");
        assertThat(MarketStateManager.symbolFromStateKey("mkt::book", ":book")).isEmpty();
        assertThat(MarketStateManager.symbolFromStateKey("mkt:BTC-USDT:trades", ":book")).isEmpty();
    }
}
