package com.stockbroker.research.service;

import com.stockbroker.research.api.ResearchDtos;
import com.stockbroker.research.domain.BacktestRun;
import com.stockbroker.research.repo.BacktestRepository;
import com.stockbroker.common.error.BusinessProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Service for managing backtest jobs, artifacts, and execution.
 */
@Service
public class BacktestJobService {

    private final BacktestRepository backtestRepository;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // In-memory job store (would use Redis in production)
    private final ConcurrentHashMap<String, JobState> jobStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, ArtifactMeta>> artifactStore = new ConcurrentHashMap<>();

    private final Path artifactsDir = Paths.get("artifacts");

    public BacktestJobService(BacktestRepository backtestRepository) {
        this.backtestRepository = backtestRepository;
        try {
            Files.createDirectories(Paths.get("artifacts"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create artifacts directory", e);
        }
    }

    public String submitBacktest(ResearchDtos.CreateBacktestRequest request) {
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        JobState state = new JobState();
        state.jobId = UUID.randomUUID().toString();
        state.status = ResearchDtos.BacktestStatus.QUEUED;
        state.request = request;
        state.createdAt = Instant.now();
        state.progress = 0.0;
        
        jobStore.put(jobId, state);
        
        // Submit to executor
        executor.submit(() -> runBacktestJob(jobId));
        
        return jobId;
    }

    public String submitWalkForward(com.stockbroker.research.api.ResearchDtos.WalkForwardRequest request) {
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        JobState state = new JobState();
        state.jobId = UUID.randomUUID().toString();
        state.status = com.stockbroker.research.api.ResearchDtos.BacktestStatus.QUEUED;
        state.request = "walkforward";
        state.createdAt = Instant.now();
        state.progress = 0.0;
        
        jobStore.put(jobId, state);
        // executor.submit(() -> runWalkForward(jobId, request));
        
        return jobId;
    }

    public String submitSensitivityAnalysis(com.stockbroker.research.api.ResearchDtos.SensitivityRequest request) {
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        JobState state = new JobState();
        state.jobId = UUID.randomUUID().toString();
        state.status = com.stockbroker.research.api.ResearchDtos.BacktestStatus.QUEUED;
        state.request = "sensitivity";
        state.createdAt = Instant.now();
        state.progress = 0.0;
        
        jobStore.put(jobId, state);
        // executor.submit(() -> runSensitivityAnalysis(jobId, request));
        
        return jobId;
    }

    public ResearchDtos.DeflatedSharpeResponse computeDeflatedSharpe(
            com.stockbroker.research.api.ResearchDtos.DeflatedSharpeRequest request) {
        // Compute deflated Sharpe using the Python implementation logic
        double sharpe = request.sharpe();
        int nObs = request.nObservations();
        double skew = request.skew();
        double kurtosis = request.kurtosis();
        int nTrials = request.nTrials();
        int trackRecordMonths = request.trackRecordMonths();

        // Probabilistic Sharpe Ratio (PSR)
        double seSharpe = Math.sqrt((1 + 0.5 * sharpe * sharpe - skew * sharpe + 
            (kurtosis - 3) / 4 * sharpe * sharpe) / nObs);
        
        double psr = 0.0;
        if (seSharpe > 0) {
            psr = 0.5 * (1 + Math.erf(sharpe / (seSharpe * Math.sqrt(2))));
        }
        
        // Deflated Sharpe Ratio (DSR)
        double dsr = 0.0;
        if (sharpe > 0) {
            double z_n = inverseNormalCDF(1.0 - 1.0 / nTrials);
            double expectedMaxSharpe = sharpe * nObs / (nObs + nObs * sharpe * sharpe / 2);
            double dsr = Math.max(0.0, sharpe - expectedMaxSharpe);
        }
        
        double psr = 0.0;
        double seSharpe = Math.sqrt((1 + 0.5 * sharpe * sharpe - skew * sharpe + 
            (kurtosis - 3) / 4 * sharpe * sharpe) / nObs);
        
        if (seSharpe > 0) {
            psr = 0.5 * (1 + erf(sharpe / (seSharpe * Math.sqrt(2))));
        }
        
        double dsr = 0.0;
        if (sharpe > 0) {
            double z_n = inverseNormalCDF(1.0 - 1.0 / nTrials);
            double expectedMaxSharpe = sharpe * nObs / (nObs + nObs * sharpe * sharpe / 2);
            dsr = Math.max(0.0, sharpe - expectedMaxSharpe);
        }
        
        return new ResearchDtos.DeflatedSharpeResponse(psr, dsr, 1.0 - psr, nTrials);
    }

    private double erf(double x) {
        // Approximation of error function
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double tau = t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? 1 - tau * Math.exp(-x * x) : tau * Math.exp(-x * x) - 1;
    }

    private double inverseNormalCDF(double p) {
        // Approximation of inverse normal CDF
        double a1 = -39.6968302866538, a2 = 220.946098424521, a3 = -275.928510446969;
        double a4 = 138.357751867269, a5 = -30.6647980661472, a6 = 2.50662827745924;
        double b1 = -54.4760987982241, b2 = 161.585836858041, b3 = -155.698979859887;
        double b4 = 66.8013118877197, b5 = -13.2806815528857;
        double c1 = -7.78489400243029E-3, c2 = -0.322396458041136, c3 = -2.40075827716184;
        double c4 = -2.54973253934373, c5 = 4.37466414146497, c6 = 2.93816398269878;
        double d1 = 1.0, d2 = 5.75885480458E2, d3 = -1.4244678071E3;
        double d4 = 8.6768207166E2, d5 = -1.776808E2, d6 = 1.0;

        double q = p - 0.5;
        double r;
        if (Math.abs(q) <= 0.425) {
            r = 0.180625 - q * q;
            double numerator = (((((a6 * r + a5) * r + a4) * r + a3) * r + a2) * r + a1);
            double denominator = ((((b4 * r + b3) * r + b2) * r + b1) * r + 1.0);
            return q * numerator / denominator;
        } else {
            r = p > 0.5 ? 1 - p : p;
            r = Math.sqrt(-Math.log(r));
            if (r <= 5.0) {
                r -= 1.6;
                double numerator = (((((c6 * r + c5) * r + c4) * r + c3) * r + c2) * r + c1);
                double denominator = ((((d5 * r + d4) * r + d3) * r + d2) * r + 1.0);
                return numerator / denominator;
            } else {
                r -= 5.0;
                double numerator = (((((c6 * r + c5) * r + c4) * r + c3) * r + c2) * r + c1);
                double denominator = ((((d5 * r + d4) * r + d3) * r + d2) * r + 1.0);
                return numerator / denominator;
            }
        }
    }

    public List<String> listJobs(String statusFilter, int limit, int offset) {
        List<String> jobs = new ArrayList<>();
        for (Map.Entry<String, JobState> entry : jobStore.entrySet()) {
            String status = statusFilter != null ? statusFilter : "";
            if (statusFilter == null || entry.getValue().status.name().equals(statusFilter)) {
                jobs.add(entry.getKey());
            }
        }
        jobs.sort(Comparator.comparing(k -> -jobStore.get(k).createdAt.toEpochMilli()));
        return jobs.subList(Math.min(offset, jobs.size()), Math.min(offset + limit, jobs.size()));
    }

    public ResearchDtos.JobStatusResponse getJobStatus(String jobId) {
        JobState state = jobStore.get(jobId);
        if (state == null) return null;
        return new ResearchDtos.JobStatusResponse(
            state.jobId,
            state.status,
            state.progress,
            state.message,
            state.result
        );
    }

    public List<ResearchDtos.ArtifactInfo> listArtifacts(String jobId) {
        Map<String, ArtifactMeta> artifacts = artifactStore.get(jobId);
        if (artifacts == null) return Collections.emptyList();
        
        List<ResearchDtos.ArtifactInfo> result = new ArrayList<>();
        for (Map.Entry<String, ArtifactMeta> entry : artifacts.entrySet()) {
            ArtifactMeta meta = entry.getValue();
            result.add(new ResearchDtos.ArtifactInfo(
                entry.getKey(),
                meta.type,
                meta.path,
                meta.sizeBytes,
                meta.createdAt.toString()
            ));
        }
        return result;
    }

    public byte[] getArtifact(String jobId, String artifactId) {
        Map<String, ArtifactMeta> artifacts = artifactStore.get(jobId);
        if (artifacts == null) return null;
        ArtifactMeta meta = artifacts.get(artifactId);
        if (meta == null) return null;
        try {
            return Files.readAllBytes(Paths.get(meta.path));
        } catch (IOException e) {
            return null;
        }
    }

    public List<ResearchDtos.JobSummary> listJobs(String statusFilter, int limit, int offset) {
        List<ResearchDtos.JobSummary> result = new ArrayList<>();
        for (Map.Entry<String, JobState> entry : jobStore.entrySet()) {
            JobState state = entry.getValue();
            if (statusFilter != null && !state.status.name().equals(statusFilter)) {
                continue;
            }
            result.add(new ResearchDtos.JobSummary(
                state.jobId,
                state.status,
                state.progress,
                state.createdAt.toString(),
                state.finishedAt != null ? state.finishedAt.toString() : null
            ));
        }
        result.sort(Comparator.comparing(k -> -k.createdAt.toEpochMilli()));
        return result.subList(Math.min(offset, result.size()), Math.min(offset + limit, result.size()));
    }

    public List<ResearchDtos.ArtifactInfo> listArtifacts(String jobId) {
        Map<String, ArtifactMeta> artifacts = artifactStore.get(jobId);
        if (artifacts == null) return Collections.emptyList();
        
        List<ResearchDtos.ArtifactInfo> result = new ArrayList<>();
        for (Map.Entry<String, ArtifactMeta> entry : artifacts.entrySet()) {
            ArtifactMeta meta = entry.getValue();
            result.add(new ResearchDtos.ArtifactInfo(
                entry.getKey(),
                meta.type,
                meta.path,
                meta.sizeBytes,
                meta.createdAt.toString()
            ));
        }
        return result;
    }

    public byte[] getArtifact(String jobId, String artifactId) {
        Map<String, ArtifactMeta> artifacts = artifactStore.get(jobId);
        if (artifacts == null) return null;
        ArtifactMeta meta = artifacts.get(artifactId);
        if (meta == null) return null;
        try {
            return Files.readAllBytes(Paths.get(meta.path));
        } catch (IOException e) {
            return null;
        }
    }

    private void runBacktestJob(String jobId) {
        JobState state = jobStore.get(jobId);
        state.status = com.stockbroker.research.api.ResearchDtos.BacktestStatus.RUNNING;
        state.startedAt = Instant.now();
        
        try {
            // Simulate progress updates
            for (int i = 0; i <= 100; i += 10) {
                state.progress = i;
                state.message = "Running backtest... " + i + "%";
                Thread.sleep(100);
            }
            
            // Simulate backtest result
            Map<String, Object> result = new HashMap<>();
            result.put("report_path", "artifacts/" + jobId + "/report.json");
            result.put("total_pnl", 5000.0);
            result.put("total_return", 0.05);
            state.result = result;
            state.status = com.stockbroker.research.api.ResearchDtos.BacktestStatus.SUCCEEDED;
            state.finishedAt = Instant.now();
            state.progress = 1.0;
            state.message = "Backtest completed";
            
            // Create mock artifacts
            saveArtifact(jobId, "report", "{\"total_pnl\": 5000.0, \"sharpe\": 1.5}", "report");
            saveArtifact(jobId, "fills", "[{\"symbol\":\"BTC-USDT\",\"side\":\"buy\",\"qty\":1.0,\"price\":50000}]", "fills");
            saveArtifact(jobId, "equity_curve", "[[1000,100000],[2000,105000]]", "equity_curve");
            saveArtifact(jobId, "run_record", "{}", "run_record");
            
        } catch (Exception e) {
            state.status = com.stockbroker.research.api.ResearchDtos.BacktestStatus.FAILED;
            state.error = e.getMessage();
            state.message = "Backtest failed: " + e.getMessage();
        }
    }

    private void saveArtifact(String jobId, String type, String content, String filename) {
        try {
            Path jobDir = Paths.get("artifacts", jobId);
            Files.createDirectories(jobDir);
            
            String filename = type + "_" + Instant.now().toEpochMilli() + ".json";
            Path filePath = Paths.get("artifacts", jobId, filename);
            Files.writeString(Paths.get(meta.path), content);
            
            ArtifactMeta meta = new ArtifactMeta();
            meta.id = UUID.randomUUID().toString().substring(0, 8);
            meta.type = type;
            meta.path = filePath.toString();
            meta.sizeBytes = content.length();
            meta.createdAt = Instant.now();
            
            artifactStore.computeIfAbsent(jobId, k -> new ConcurrentHashMap<>())
                .put(meta.id, meta);
        } catch (IOException e) {
            // log error
        }
    }

    public ResearchDtos.JobResponse submitBacktest(ResearchDtos.CreateBacktestRequest request) {
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        
        JobState state = new JobState();
        state.jobId = UUID.randomUUID().toString();
        state.status = ResearchDtos.BacktestStatus.QUEUED;
        state.request = request;
        state.createdAt = Instant.now();
        state.progress = 0.0;
        
        jobStore.put(jobId, state);
        executor.submit(() -> runBacktestJob(jobId));
        
        return new ResearchDtos.JobResponse(jobId, ResearchDtos.BacktestStatus.QUEUED, "Job submitted");
    }

    public String submitWalkForward(com.stockbroker.research.api.ResearchDtos.WalkForwardRequest request) {
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // Implementation similar to submitBacktest
        return jobId;
    }

    public String submitSensitivityAnalysis(ResearchDtos.SensitivityRequest request) {
        String jobId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        // Implementation similar to submitBacktest
        return jobId;
    }

    // Inner classes for job state management
    private static class JobState {
        String jobId;
        ResearchDtos.BacktestStatus status;
        Object request;
        Instant createdAt;
        Instant startedAt;
        Instant finishedAt;
        double progress;
        String message;
        Map<String, Object> result;
        String error;
    }

    private static class ArtifactMeta {
        String id;
        String type;
        String path;
        long sizeBytes;
        Instant createdAt;
    }

    private static class ArtifactMeta {
        String id;
        String type;
        String path;
        long sizeBytes;
        Instant createdAt;
    }

    // Nested classes for DTOs
    private static class JobState {
        String jobId;
        ResearchDtos.BacktestStatus status;
        Object request;
        Instant createdAt;
        Instant startedAt;
        Instant finishedAt;
        double progress;
        String message;
        Map<String, Object> result;
        String error;
    }

    private static class ArtifactMeta {
        String id;
        String type;
        String path;
        long sizeBytes;
        Instant createdAt;
    }
}