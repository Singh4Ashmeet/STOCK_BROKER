package com.stockbroker.research.service;

import com.stockbroker.research.api.ResearchDtos;
import com.stockbroker.research.domain.BacktestRun;
import com.stockbroker.research.repo.BacktestRepository;
import com.stockbroker.common.error.BusinessProblem;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Backtest service.
 */
@Service
@Transactional
public class BacktestService {

    private final BacktestRepository backtestRepository;

    public BacktestService(BacktestRepository backtestRepository) {
        this.backtestRepository = backtestRepository;
    }

    public ResearchDtos.ListStrategiesResponse listStrategies() {
        // Return available strategies from Python wheel
        // For now, return a static list
        List<ResearchDtos.StrategyInfo> strategies = List.of(
                new ResearchDtos.StrategyInfo(
                        "momentum",
                        "Momentum trading strategy",
                        Map.of(
                                "lookback", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                                "threshold", Map.of("type", "number", "minimum", 0.0, "maximum", 1.0)
                        )
                ),
                new ResearchDtos.StrategyInfo(
                        "mean_reversion",
                        "Mean reversion strategy",
                        Map.of(
                                "window", Map.of("type", "integer", "minimum", 5, "maximum", 200),
                                "num_std", Map.of("type", "number", "minimum", 0.5, "maximum", 5.0)
                        )
                )
        );
        return new ResearchDtos.ListStrategiesResponse(strategies);
    }

    public ResearchDtos.CreateBacktestResponse createBacktest(UUID userId,
                                                               ResearchDtos.CreateBacktestRequest request,
                                                               String idempotencyKey) {
        // Check for duplicate idempotency key
        // TODO: Implement idempotency check

        BacktestRun run = new BacktestRun();
        run.setUserId(userId);
        run.setStrategy(request.strategy());
        run.setParams(request.params());
        run.setSymbol(request.symbol());
        run.setDataFrom(Instant.parse(request.from()));
        run.setDataTo(Instant.parse(request.to()));
        run.setFeeModel(request.feeModel());
        run.setLatencyMs(request.latencyMs());
        run.setStatus("QUEUED");
        run.setCodeVersion("0.1.0");
        run.setPybookVersion("0.1.0");

        backtestRepository.save(run);

        // TODO: Submit to Python worker queue
        // For now, return the run ID
        return new ResearchDtos.CreateBacktestResponse(run.getId().toString(), ResearchDtos.BacktestStatus.QUEUED);
    }

    public ResearchDtos.BacktestListResponse listBacktests(UUID userId, int limit, String cursor) {
        List<BacktestRun> runs = backtestRepository.findByUserId(userId, PageRequest.of(0, limit));
        List<ResearchDtos.BacktestResult> responses = runs.stream()
                .map(this::toBacktestResult)
                .collect(Collectors.toList());
        return new ResearchDtos.BacktestListResponse(responses, null);
    }

    public ResearchDtos.BacktestResult getBacktest(UUID userId, UUID runId) {
        BacktestRun run = backtestRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Backtest not found",
                        java.util.Map.of("code", "BACKTEST_NOT_FOUND")));
        return toBacktestResult(run);
    }

    public ResearchDtos.BacktestResult getBacktestResult(UUID userId, UUID runId) {
        return getBacktest(userId, runId);
    }

    public ResearchDtos.BacktestTradeLogResponse getTradeLog(UUID userId, UUID runId, int limit, String cursor) {
        BacktestRun run = backtestRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new BusinessProblem.ValidationFailed("Backtest not found",
                        java.util.Map.of("code", "BACKTEST_NOT_FOUND")));

        // TODO: Load trade log from artifact_path
        // For now, return empty
        return new ResearchDtos.BacktestTradeLogResponse(List.of(), null);
    }

    private ResearchDtos.BacktestResult toBacktestResult(BacktestRun run) {
        ResearchDtos.BacktestSummary summary = null;
        if (run.getSummary() != null) {
            Map<String, Object> s = run.getSummary();
            summary = new ResearchDtos.BacktestSummary(
                    ((Number) s.getOrDefault("total_return", 0)).doubleValue(),
                    ((Number) s.getOrDefault("sharpe_ratio", 0)).doubleValue(),
                    ((Number) s.getOrDefault("max_drawdown", 0)).doubleValue(),
                    ((Number) s.getOrDefault("win_rate", 0)).doubleValue(),
                    ((Number) s.getOrDefault("total_trades", 0)).intValue(),
                    ((Number) s.getOrDefault("profit_factor", 0)).doubleValue()
            );
        }

        return new ResearchDtos.BacktestResult(
                run.getId().toString(),
                ResearchDtos.BacktestStatus.valueOf(run.getStatus()),
                run.getStrategy(),
                run.getParams(),
                run.getSymbol(),
                run.getDataFrom().toString(),
                run.getDataTo().toString(),
                run.getFeeModel(),
                run.getLatencyMs(),
                run.getError(),
                run.getCodeVersion(),
                run.getPybookVersion(),
                run.getSeed() != null ? run.getSeed() : 0,
                summary,
                run.getArtifactPath(),
                run.getCreatedAt().toString(),
                run.getFinishedAt() != null ? run.getFinishedAt().toString() : null
        );
    }
}