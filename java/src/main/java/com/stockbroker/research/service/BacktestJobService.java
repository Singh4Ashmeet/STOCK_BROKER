package com.stockbroker.research.service;

import com.stockbroker.research.api.ResearchDtos;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages asynchronous backtest jobs and their generated artifacts.
 *
 * <p>The job state is deliberately in-memory for the current paper-trading
 * deployment. A production deployment should replace these maps with durable
 * job and artifact stores.</p>
 */
@Service
public class BacktestJobService {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentMap<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, ArtifactMeta>> artifacts = new ConcurrentHashMap<>();
    private final Path artifactsDirectory = Path.of("artifacts");

    public BacktestJobService() {
        try {
            Files.createDirectories(artifactsDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create artifacts directory", exception);
        }
    }

    public String submitBacktest(ResearchDtos.CreateBacktestRequest request) {
        return createJob("backtest", request, true);
    }

    public String submitWalkForward(ResearchDtos.WalkForwardRequest request) {
        return createJob("walk-forward", request, false);
    }

    public String submitSensitivityAnalysis(ResearchDtos.SensitivityRequest request) {
        return createJob("sensitivity", request, false);
    }

    private String createJob(String type, Object request, boolean execute) {
        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState(jobId, type, request, Instant.now());
        jobs.put(jobId, state);
        if (execute) {
            executor.submit(() -> runBacktestJob(state));
        }
        return jobId;
    }

    public ResearchDtos.JobStatusResponse getJobStatus(String jobId) {
        JobState state = jobs.get(jobId);
        return state == null ? null : state.toStatusResponse();
    }

    public List<ResearchDtos.JobSummary> listJobs(String statusFilter, int limit, int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        return jobs.values().stream()
                .filter(job -> statusFilter == null || job.status.name().equalsIgnoreCase(statusFilter))
                .sorted(Comparator.comparing((JobState job) -> job.createdAt).reversed())
                .skip(safeOffset)
                .limit(safeLimit)
                .map(job -> job.toSummary())
                .toList();
    }

    public List<ResearchDtos.ArtifactInfo> listArtifacts(String jobId) {
        Map<String, ArtifactMeta> jobArtifacts = artifacts.get(jobId);
        if (jobArtifacts == null) {
            return List.of();
        }
        return jobArtifacts.values().stream()
                .sorted(Comparator.comparing(meta -> meta.createdAt()))
                .map(meta -> new ResearchDtos.ArtifactInfo(
                        meta.id(), meta.type(), meta.sizeBytes(), meta.createdAt().toString()))
                .toList();
    }

    public byte[] getArtifact(String jobId, String artifactId) {
        Map<String, ArtifactMeta> jobArtifacts = artifacts.get(jobId);
        ArtifactMeta artifact = jobArtifacts == null ? null : jobArtifacts.get(artifactId);
        if (artifact == null) {
            return null;
        }
        try {
            return Files.readAllBytes(artifact.path());
        } catch (IOException exception) {
            return null;
        }
    }

    public ResearchDtos.DeflatedSharpeResponse computeDeflatedSharpe(
            ResearchDtos.DeflatedSharpeRequest request) {
        int observations = Math.max(request.nObservations(), 1);
        int trials = Math.max(request.nTrials(), 1);
        double variance = (1.0 + 0.5 * request.sharpe() * request.sharpe()
                - request.skew() * request.sharpe()
                + ((request.kurtosis() - 3.0) / 4.0) * request.sharpe() * request.sharpe()) / observations;
        double standardError = Math.sqrt(Math.max(variance, 0.0));
        double psr = standardError == 0.0 ? (request.sharpe() > 0.0 ? 1.0 : 0.5)
                : normalCdf(request.sharpe() / standardError);
        double expectedMaximum = inverseNormalCdf(1.0 - 1.0 / (trials + 1.0));
        double dsr = standardError == 0.0 ? request.sharpe()
                : normalCdf((request.sharpe() - expectedMaximum * standardError) / standardError);
        return new ResearchDtos.DeflatedSharpeResponse(psr, dsr, 1.0 - psr, trials);
    }

    private void runBacktestJob(JobState state) {
        state.status = ResearchDtos.BacktestStatus.RUNNING;
        state.message = "Running backtest";
        state.updatedAt = Instant.now();
        try {
            for (int progress = 10; progress <= 100; progress += 10) {
                state.progress = progress / 100.0;
                state.message = "Running backtest... " + progress + "%";
                state.updatedAt = Instant.now();
                Thread.sleep(10);
            }
            saveArtifact(state.jobId, "report", "{\"total_pnl\":5000.0,\"sharpe\":1.5}");
            saveArtifact(state.jobId, "fills", "[]");
            state.status = ResearchDtos.BacktestStatus.SUCCEEDED;
            state.message = "Backtest completed";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            state.status = ResearchDtos.BacktestStatus.FAILED;
            state.message = "Backtest interrupted";
        } catch (RuntimeException exception) {
            state.status = ResearchDtos.BacktestStatus.FAILED;
            state.message = "Backtest failed: " + exception.getMessage();
        } finally {
            state.updatedAt = Instant.now();
        }
    }

    private void saveArtifact(String jobId, String type, String content) {
        try {
            Path jobDirectory = artifactsDirectory.resolve(jobId);
            Files.createDirectories(jobDirectory);
            String artifactId = UUID.randomUUID().toString();
            Path path = jobDirectory.resolve(type + "-" + artifactId + ".json");
            Files.writeString(path, content);
            ArtifactMeta meta = new ArtifactMeta(artifactId, type, path, content.length(), Instant.now());
            artifacts.computeIfAbsent(jobId, ignored -> new ConcurrentHashMap<>()).put(artifactId, meta);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save " + type + " artifact", exception);
        }
    }

    private static double normalCdf(double value) {
        return 0.5 * (1.0 + erf(value / Math.sqrt(2.0)));
    }

    private static double erf(double value) {
        double sign = Math.signum(value);
        double x = Math.abs(value);
        double t = 1.0 / (1.0 + 0.3275911 * x);
        double approximation = 1.0 - (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x));
        return sign * approximation;
    }

    private static double inverseNormalCdf(double probability) {
        double p = Math.min(Math.max(probability, 1.0e-12), 1.0 - 1.0e-12);
        double a1 = -39.6968302866538;
        double a2 = 220.946098424521;
        double a3 = -275.928510446969;
        double a4 = 138.357751867269;
        double a5 = -30.6647980661472;
        double a6 = 2.50662827745924;
        double b1 = -54.4760987982241;
        double b2 = 161.585836858041;
        double b3 = -155.698979859887;
        double b4 = 66.8013118877197;
        double b5 = -13.2806815528857;
        double q = p - 0.5;
        if (Math.abs(q) <= 0.425) {
            double r = 0.180625 - q * q;
            return q * (((((a6 * r + a5) * r + a4) * r + a3) * r + a2) * r + a1)
                    / (((((b5 * r + b4) * r + b3) * r + b2) * r + b1) * r + 1.0);
        }
        double r = Math.sqrt(-Math.log(q < 0.0 ? p : 1.0 - p));
        return (q < 0.0 ? -1.0 : 1.0) * r;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static final class JobState {
        private final String jobId;
        private final String type;
        @SuppressWarnings("unused")
        private final Object request;
        private final Instant createdAt;
        private volatile ResearchDtos.BacktestStatus status = ResearchDtos.BacktestStatus.QUEUED;
        private volatile double progress;
        private volatile String message = "Job queued";
        private volatile Instant updatedAt;

        private JobState(String jobId, String type, Object request, Instant createdAt) {
            this.jobId = jobId;
            this.type = type;
            this.request = request;
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }

        private ResearchDtos.JobStatusResponse toStatusResponse() {
            return new ResearchDtos.JobStatusResponse(jobId, status, progress, message,
                    createdAt.toString(), updatedAt.toString());
        }

        private ResearchDtos.JobSummary toSummary() {
            return new ResearchDtos.JobSummary(jobId, type, status, createdAt.toString());
        }
    }

    private record ArtifactMeta(String id, String type, Path path, long sizeBytes, Instant createdAt) { }
}
