package com.stockbroker.research.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.Map;

/**
 * Research & Backtesting API DTOs.
 */
public final class ResearchDtos {

    private ResearchDtos() {}

    public enum BacktestStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

    public enum OptimizationMethod { GRID, RANDOM, SOBOL }

    public enum WindowType { ROLLING, ANCHORED }

    public record CreateBacktestRequest(
            @NotBlank String strategyName,
            @NotBlank String symbol,
            @NotNull @JsonProperty("start_date") String startDate,
            @NotNull @JsonProperty("end_date") String endDate,
            @JsonProperty("params") Map<String, String> params,
            @JsonProperty("idempotency_key") String idempotencyKey,
            @JsonProperty("config_hash") String configHash,
            @JsonProperty("data_fingerprint") String dataFingerprint,
            @JsonProperty("git_commit") String gitCommit
    ) {}

    public record CreateBacktestResponse(
            @JsonProperty("backtest_id") String backtestId,
            BacktestStatus status,
            String message
    ) {}

    public record BacktestSummary(
            @JsonProperty("total_return") double totalReturn,
            @JsonProperty("annualized_return") double annualizedReturn,
            @JsonProperty("volatility") double volatility,
            @JsonProperty("sharpe_ratio") double sharpeRatio,
            @JsonProperty("sortino_ratio") double sortinoRatio,
            @JsonProperty("max_drawdown") double maxDrawdown,
            @JsonProperty("calmar_ratio") double calmarRatio,
            @JsonProperty("win_rate") double winRate,
            @JsonProperty("profit_factor") double profitFactor,
            @JsonProperty("total_trades") int totalTrades,
            @JsonProperty("avg_trade_pnl") double avgTradePnl
    ) {}

    public record BacktestResult(
            @JsonProperty("backtest_id") String backtestId,
            String symbol,
            @JsonProperty("strategy_name") String strategyName,
            @JsonProperty("start_date") String startDate,
            @JsonProperty("end_date") String endDate,
            @JsonProperty("config_hash") String configHash,
            @JsonProperty("data_fingerprint") String dataFingerprint,
            String codeVersion,
            String pybookVersion,
            long seed,
            BacktestSummary summary,
            @JsonProperty("artifact_path") String artifactPath,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("finished_at") String finishedAt
    ) {}

    public record BacktestListResponse(
            List<BacktestResult> backtests,
            String nextCursor
    ) {}

    public record TradeLogEntry(
            String timestamp,
            String symbol,
            String side,
            String price,
            String qty,
            String fee,
            String pnl
    ) {}

    public record BacktestTradeLogResponse(
            List<TradeLogEntry> trades,
            String nextCursor
    ) {}

    // Backtest job management DTOs
    public record JobResponse(
            String jobId,
            BacktestStatus status,
            String message
    ) {}

    public record JobStatusResponse(
            String jobId,
            BacktestStatus status,
            double progress,
            String message,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt
    ) {}

    public record JobSummary(
            String jobId,
            String type,
            BacktestStatus status,
            @JsonProperty("created_at") String createdAt
    ) {}

    public record JobListResponse(
            List<JobSummary> jobs,
            String nextCursor
    ) {}

    // Artifact DTOs
    public record ArtifactInfo(
            String id,
            String type,
            long size,
            @JsonProperty("created_at") String createdAt
    ) {}

    public record ArtifactListResponse(
            List<ArtifactInfo> artifacts
    ) {}

    // Walk-forward DTOs
    public record WalkForwardRequest(
            @NotBlank @JsonProperty("strategy_name") String strategyName,
            @NotBlank String symbol,
            @NotNull @JsonProperty("start_date") String startDate,
            @NotNull @JsonProperty("end_date") String endDate,
            @JsonProperty("params") Map<String, String> params,
            @NotNull @JsonProperty("window_type") WindowType windowType,
            @Positive @JsonProperty("train_size") int trainSize,
            @Positive @JsonProperty("test_size") int testSize,
            @Positive @JsonProperty("step_size") int stepSize,
            @NotNull @JsonProperty("optimization_method") OptimizationMethod optimizationMethod,
            @JsonProperty("optimization_params") Map<String, Object> optimizationParams,
            @JsonProperty("n_trials") int nTrials,
            @JsonProperty("n_jobs") int nJobs
    ) {}

    // Sensitivity analysis DTOs
    public record SensitivityRequest(
            @NotBlank @JsonProperty("strategy_name") String strategyName,
            @NotBlank String symbol,
            @NotNull @JsonProperty("start_date") String startDate,
            @NotNull @JsonProperty("end_date") String endDate,
            @JsonProperty("base_params") Map<String, String> baseParams,
            @JsonProperty("param_ranges") Map<String, ParamRange> paramRanges,
            @NotNull @JsonProperty("method") OptimizationMethod method,
            @Positive @JsonProperty("n_trials") int nTrials,
            @JsonProperty("n_jobs") int nJobs
    ) {}

    public record ParamRange(
            @JsonProperty("min") double min,
            @JsonProperty("max") double max,
            @JsonProperty("step") double step
    ) {}

    public record SensitivityResult(
            @JsonProperty("param_name") String paramName,
            @JsonProperty("param_value") double paramValue,
            @JsonProperty("sharpe_ratio") double sharpeRatio,
            @JsonProperty("total_return") double totalReturn,
            @JsonProperty("max_drawdown") double maxDrawdown
    ) {}

    public record SensitivityResponse(
            List<SensitivityResult> results,
            @JsonProperty("correlation_matrix") Map<String, Map<String, Double>> correlationMatrix
    ) {}

    // Deflated Sharpe DTOs
    public record DeflatedSharpeRequest(
            double sharpe,
            @JsonProperty("n_observations") int nObservations,
            double skew,
            double kurtosis,
            @JsonProperty("n_trials") int nTrials
    ) {}

    public record DeflatedSharpeResponse(
            double psr,
            double dsr,
            @JsonProperty("p_value") double pValue,
            @JsonProperty("n_trials") int nTrials
    ) {}

    // List strategies response
    public record StrategyInfo(
            String name,
            String description,
            @JsonProperty("param_schema") Map<String, Object> paramSchema
    ) {}

    public record ListStrategiesResponse(
            List<StrategyInfo> strategies
    ) {}
}