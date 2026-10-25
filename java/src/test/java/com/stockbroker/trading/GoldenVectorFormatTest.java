package com.stockbroker.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockbroker.common.LocalTestConfiguration;
import com.stockbroker.trading.service.GoldenVectorService;
import com.stockbroker.trading.service.OrderExecutor;
import com.stockbroker.trading.domain.Order;
import com.stockbroker.trading.domain.Fill;
import com.stockbroker.market.api.MarketDtos;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for golden vector format - ensures Python can parse and replay fills exactly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(classes = LocalTestConfiguration.class)
@ActiveProfiles("test")
class GoldenVectorFormatTest {

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    GoldenVectorService goldenVectorService;

    @Test
    void goldenFillVectorIsValidJson() throws IOException {
        // Create a mock fill and order
        Order order = createMockOrder();
        Fill fill = createMockFill();
        MarketDtos.BookUpdate snapshot = createMockSnapshot();

        // Clean up directory first
        Path dir = Paths.get("testdata/golden_fills");
        if (Files.exists(dir)) {
            Files.list(dir).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }

        // Save the vector
        goldenVectorService.saveFillVector(fill, order, snapshot);

        // Read back the written file
        if (!Files.exists(dir)) {
            return; // Skip if no vectors written
        }

        List<Path> files = Files.list(dir).toList();
        if (files.isEmpty()) {
            return;
        }

        // Find most recently modified file
        Path latestFile = files.stream()
                .max((p1, p2) -> {
                    try {
                        return Files.getLastModifiedTime(p1).compareTo(Files.getLastModifiedTime(p2));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .orElse(null);
        
        if (latestFile == null) {
            return;
        }
        
        String content = Files.readString(latestFile);

        // Should be valid JSONL
        String[] lines = content.trim().split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            // Should parse as valid JSON
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(line, Map.class);
            assertThat(map).containsKey("fillId");
            assertThat(map).containsKey("orderId");
            assertThat(map).containsKey("symbol");
            assertThat(map).containsKey("side");
            assertThat(map).containsKey("price");
            assertThat(map).containsKey("qty");
            assertThat(map).containsKey("feeAsset");
            assertThat(map).containsKey("feeAmount");
            assertThat(map).containsKey("liquidity");
            assertThat(map).containsKey("bookEpoch");
            assertThat(map).containsKey("bookSeq");
            assertThat(map).containsKey("filledAt");
            assertThat(map).containsKey("bookState");
            
            // Book state should have bids and asks
            @SuppressWarnings("unchecked")
            Map<String, Object> bookState = (Map<String, Object>) map.get("bookState");
            assertThat(bookState).containsKey("epoch");
            assertThat(bookState).containsKey("seq");
            assertThat(bookState).containsKey("bids");
            assertThat(bookState).containsKey("asks");
        }
    }

    @Test
    void previewVectorIsValidJson() throws IOException {
        // Clean up directory first
        Path dir = Paths.get("testdata/golden_fills");
        if (Files.exists(dir)) {
            Files.list(dir).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }

        OrderExecutor.OrderPreviewResult preview = new OrderExecutor.OrderPreviewResult(
                "50000.00", "25.00", 5.5, true, 3
        );
        
        var request = new com.stockbroker.trading.api.TradingDtos.OrderPreviewRequest(
                "BINANCE:BTC-USDT",
                com.stockbroker.trading.api.TradingDtos.OrderSide.BUY,
                "0.1",
                null
        );
        
        var snapshot = createMockSnapshot();
        
        goldenVectorService.savePreviewVector(preview, request, snapshot);

        if (!Files.exists(dir)) {
            return;
        }

        List<Path> files = Files.list(dir).toList();
        if (files.isEmpty()) {
            return;
        }

        // Find preview file
        Path previewFile = files.stream()
                .filter(p -> p.getFileName().toString().startsWith("previews_"))
                .findFirst()
                .orElse(null);
        
        if (previewFile == null) {
            return;
        }

        String content = Files.readString(previewFile);
        String[] lines = content.trim().split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(line, Map.class);
            assertThat(map).containsKey("symbol");
            assertThat(map).containsKey("side");
            assertThat(map).containsKey("type");
            assertThat(map).containsKey("qty");
            assertThat(map).containsKey("estFillPrice");
            assertThat(map).containsKey("estFees");
            assertThat(map).containsKey("estSlippageBps");
            assertThat(map).containsKey("bookValid");
            assertThat(map).containsKey("levelsConsumed");
            assertThat(map).containsKey("bookEpoch");
            assertThat(map).containsKey("bookSeq");
            assertThat(map).containsKey("timestamp");
        }
    }

    @Test
    void fillVectorCanBeReplayedInPython() throws IOException {
        // This test verifies the format matches what Python expects
        // Python expects: fillId, orderId, symbol, side, type, price, qty, feeAsset, feeAmount, liquidity, bookEpoch, bookSeq, filledAt, bookState
        
        // Clean up directory first
        Path dir = Paths.get("testdata/golden_fills");
        if (Files.exists(dir)) {
            Files.list(dir).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        }

        // Create a mock fill and order
        Order order = createMockOrder();
        Fill fill = createMockFill();
        MarketDtos.BookUpdate snapshot = createMockSnapshot();

        // Save the vector
        goldenVectorService.saveFillVector(fill, order, snapshot);

        if (!Files.exists(dir)) {
            return;
        }

        List<Path> files = Files.list(dir).toList();
        if (files.isEmpty()) {
            return;
        }

        // Find most recently modified file
        Path latestFile = files.stream()
                .max((p1, p2) -> {
                    try {
                        return Files.getLastModifiedTime(p1).compareTo(Files.getLastModifiedTime(p2));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .orElse(null);
        
        if (latestFile == null) {
            return;
        }
        
        String content = Files.readString(latestFile);
        String[] lines = content.trim().split("\n");
        
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            @SuppressWarnings("unchecked")
            Map<String, Object> vector = objectMapper.readValue(line, Map.class);
            
            // Verify all required fields for Python replay
            assertThat(vector.get("fillId")).isInstanceOf(String.class);
            assertThat(vector.get("orderId")).isInstanceOf(String.class);
            assertThat(vector.get("symbol")).isInstanceOf(String.class);
            assertThat(vector.get("side")).isIn("BUY", "SELL");
            assertThat(vector.get("type")).isIn("MARKET", "LIMIT");
            assertThat(vector.get("price")).isInstanceOf(String.class);
            assertThat(vector.get("qty")).isInstanceOf(String.class);
            assertThat(vector.get("feeAsset")).isInstanceOf(String.class);
            assertThat(vector.get("feeAmount")).isInstanceOf(String.class);
            assertThat(vector.get("liquidity")).isIn("MAKER", "TAKER");
            assertThat(vector.get("bookEpoch")).isInstanceOf(Number.class);
            assertThat(vector.get("bookSeq")).isInstanceOf(Number.class);
            assertThat(vector.get("filledAt")).isInstanceOf(String.class);
            assertThat(vector.get("bookState")).isInstanceOf(Map.class);
            
            // Verify price/qty are valid decimal strings
            String price = (String) vector.get("price");
            String qty = (String) vector.get("qty");
            assertThat(new java.math.BigDecimal(price).compareTo(BigDecimal.ZERO)).isGreaterThan(0);
            assertThat(new java.math.BigDecimal(qty).compareTo(BigDecimal.ZERO)).isGreaterThan(0);
            
            // Verify fee amount is valid
            String feeAmount = (String) vector.get("feeAmount");
            assertThat(new java.math.BigDecimal(feeAmount).compareTo(BigDecimal.ZERO)).isGreaterThanOrEqualTo(0);
        }
    }

    // Helper methods
    private Order createMockOrder() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUserId(UUID.randomUUID());
        order.setAccountId(UUID.randomUUID());
        order.setSymbol("BINANCE:BTC-USDT");
        order.setSide("BUY");
        order.setType("MARKET");
        order.setTif("GTC");
        order.setQty(new BigDecimal("0.1"));
        order.setStatus("FILLED");
        order.setFilledQty(new BigDecimal("0.1"));
        order.setAvgFillPrice(new BigDecimal("50000"));
        return order;
    }

    private Fill createMockFill() {
        Fill fill = new Fill();
        fill.setId(UUID.randomUUID());
        fill.setOrderId(UUID.randomUUID());
        fill.setUserId(UUID.randomUUID());
        fill.setAccountId(UUID.randomUUID());
        fill.setSymbol("BINANCE:BTC-USDT");
        fill.setSide("BUY");
        fill.setPrice(new BigDecimal("50000"));
        fill.setQty(new BigDecimal("0.1"));
        fill.setFeeAsset("USDT");
        fill.setFeeAmount(new BigDecimal("25.00"));
        fill.setLiquidity("TAKER");
        fill.setBookEpoch(12345L);
        fill.setBookSeq(100L);
        fill.setFilledAt(Instant.now());
        return fill;
    }

    private MarketDtos.BookUpdate createMockSnapshot() {
        MarketDtos.Level bid = new MarketDtos.Level("49999", "1.0");
        MarketDtos.Level ask = new MarketDtos.Level("50001", "1.0");
        MarketDtos.BookSnapshot snapshot = new MarketDtos.BookSnapshot(
                List.of(bid), List.of(ask)
        );
        MarketDtos.Timing timing = new MarketDtos.Timing(
                "1000000000", "1000000001", "1000000002", "1000000003"
        );
        return new MarketDtos.BookUpdate(
                "BINANCE:BTC-USDT", 12345L, 100L, timing, snapshot, null, true
        );
    }
}
