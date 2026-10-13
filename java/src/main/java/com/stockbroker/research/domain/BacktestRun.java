package com.stockbroker.research.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Backtest run entity.
 */
@Entity
@Table(name = "backtest_runs")
public class BacktestRun {

    @Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "strategy", nullable = false)
    private String strategy;

    @Column(name = "params", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "symbol", nullable = false)
    private String symbol;

    @Column(name = "data_from", nullable = false)
    private Instant dataFrom;

    @Column(name = "data_to", nullable = false)
    private Instant dataTo;

    @Column(name = "fee_model", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> feeModel;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "error")
    private String error;

    @Column(name = "code_version")
    private String codeVersion;

    @Column(name = "pybook_version")
    private String pybookVersion;

    @Column(name = "seed")
    private Long seed;

    @Column(name = "summary", columnDefinition = "jsonb")
    private Map<String, Object> summary;

    @Column(name = "artifact_path")
    private String artifactPath;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public Instant getDataFrom() { return dataFrom; }
    public void setDataFrom(Instant dataFrom) { this.dataFrom = dataFrom; }
    public Instant getDataTo() { return dataTo; }
    public void setDataTo(Instant dataTo) { this.dataTo = dataTo; }
    public Map<String, Object> getFeeModel() { return feeModel; }
    public void setFeeModel(Map<String, Object> feeModel) { this.feeModel = feeModel; }
    public int getLatencyMs() { return latencyMs; }
    public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getCodeVersion() { return codeVersion; }
    public void setCodeVersion(String codeVersion) { this.codeVersion = codeVersion; }
    public String getPybookVersion() { return pybookVersion; }
    public void setPybookVersion(String pybookVersion) { this.pybookVersion = pybookVersion; }
    public Long getSeed() { return seed; }
    public void setSeed(Long seed) { this.seed = seed; }
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = summary; }
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}