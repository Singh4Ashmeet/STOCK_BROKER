package com.stockbroker.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockbroker.market.api.MarketDtos;
import com.stockbroker.trading.api.TradingDtos;
import com.stockbroker.trading.domain.Fill;
import com.stockbroker.trading.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Service for saving golden fill vectors that Python backtesting can reuse.
 * 
 * <p>Format: JSON lines, one fill per line with complete book state at fill time.
 * This enables exact replay in Python backtests.
 */
@Service
public class GoldenVectorService {

    private static final Logger log = LoggerFactory.getLogger(GoldenVectorService.class);

    private final ObjectMapper objectMapper;
    private final Path outputDir;

    public GoldenVectorService(@Value("${sb.golden-vectors.dir:testdata/golden_fills}") String outputDir) {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.outputDir = Paths.get(outputDir);
        
        try {
            Files.createDirectories(this.outputDir);
        } catch (IOException e) {
            log.warn("Failed to create golden vectors directory: {}", e.getMessage());
        }
    }

    /**
     * Save a fill with complete book state for golden vector replay.
     */
    public void saveFillVector(Fill fill, Order order, MarketDtos.BookUpdate snapshot) {
        try {
            GoldenFillVector vector = GoldenFillVector.from(fill, order, snapshot);
            
            String fileName = String.format("fills_%s.jsonl", 
                    Instant.now().toString().substring(0, 10).replace("-", ""));
            Path filePath = outputDir.resolve(fileName);
            
            String json = objectMapper.writeValueAsString(vector) + "\n";
            Files.write(filePath, json.getBytes(), 
                    java.nio.file.StandardOpenOption.CREATE, 
                    java.nio.file.StandardOpenOption.APPEND);
            
            log.debug("Saved golden vector for fill {}", fill.getId());
        } catch (IOException e) {
            log.warn("Failed to save golden vector for fill {}: {}", fill.getId(), e.getMessage());
        }
    }

    /**
     * Save a preview vector for backtest validation.
     */
    public void savePreviewVector(OrderExecutor.OrderPreviewResult preview, 
                                   TradingDtos.OrderPreviewRequest request,
                                   MarketDtos.BookUpdate snapshot) {
        try {
            PreviewVector vector = new PreviewVector(
                    request.symbol(),
                    request.side().name(),
                    request.limitPrice() != null ? "LIMIT" : "MARKET",
                    request.qty(),
                    request.limitPrice(),
                    preview.estFillPrice(),
                    preview.estFees(),
                    preview.estSlippageBps(),
                    preview.bookValid(),
                    preview.levelsConsumed(),
                    snapshot != null ? snapshot.epoch() : 0,
                    snapshot != null ? snapshot.seq() : 0,
                    Instant.now()
            );
            
            String fileName = String.format("previews_%s.jsonl", 
                    Instant.now().toString().substring(0, 10).replace("-", ""));
            Path filePath = outputDir.resolve(fileName);
            
            String json = objectMapper.writeValueAsString(vector) + "\n";
            Files.write(filePath, json.getBytes(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to save preview vector: {}", e.getMessage());
        }
    }

    // Data classes for JSON serialization
    public record GoldenFillVector(
            String fillId,
            String orderId,
            String symbol,
            String side,
            String type,
            String price,
            String qty,
            String feeAsset,
            String feeAmount,
            String liquidity,
            Long bookEpoch,
            Long bookSeq,
            Instant filledAt,
            Map<String, Object> bookState  // snapshot at fill time
    ) {
        public static GoldenFillVector from(Fill fill, Order order, MarketDtos.BookUpdate snapshot) {
            Map<String, Object> bookState = Map.of(
                    "epoch", snapshot.epoch(),
                    "seq", snapshot.seq(),
                    "bids", snapshot.snapshot().bids().stream()
                            .map(l -> Map.of("price", l.price(), "qty", l.qty()))
                            .toList(),
                    "asks", snapshot.snapshot().asks().stream()
                            .map(l -> Map.of("price", l.price(), "qty", l.qty()))
                            .toList()
            );

            return new GoldenFillVector(
                    fill.getId().toString(),
                    order.getId().toString(),
                    fill.getSymbol(),
                    fill.getSide(),
                    order.getType(),
                    fill.getPrice().toPlainString(),
                    fill.getQty().toPlainString(),
                    fill.getFeeAsset(),
                    fill.getFeeAmount().toPlainString(),
                    fill.getLiquidity(),
                    fill.getBookEpoch(),
                    fill.getBookSeq(),
                    fill.getFilledAt(),
                    bookState
            );
        }
    }

    public record PreviewVector(
            String symbol,
            String side,
            String type,
            String qty,
            String limitPrice,
            String estFillPrice,
            String estFees,
            double estSlippageBps,
            boolean bookValid,
            int levelsConsumed,
            Long bookEpoch,
            Long bookSeq,
            Instant timestamp
    ) {}
}