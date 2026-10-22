package com.stockbroker.research.api;

import com.stockbroker.common.security.CurrentUser;
import com.stockbroker.research.service.BacktestJobService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Backtest job management endpoints.
 * Provides submit, poll, fetch and artifact download for backtest jobs.
 */
@RestController
@RequestMapping("/api/v1/backtest-jobs")
@PreAuthorize("isAuthenticated()")
public class BacktestController {

    private final BacktestJobService jobService;

    public BacktestController(BacktestJobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Submit a new backtest job.
     * Returns job ID for polling.
     */
    @PostMapping
    public ResponseEntity<ResearchDtos.JobResponse> submitBacktest(
            @Valid @RequestBody ResearchDtos.CreateBacktestRequest request) {
        String jobId = jobService.submitBacktest(CurrentUser.getUserId(), request);
        return ResponseEntity.ok(new ResearchDtos.JobResponse(jobId, ResearchDtos.BacktestStatus.QUEUED, "Job submitted"));
    }

    /**
     * Get job status.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<ResearchDtos.JobStatusResponse> getJobStatus(@PathVariable String jobId) {
        ResearchDtos.JobStatusResponse status = jobService.getJobStatus(CurrentUser.getUserId(), jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * List artifacts for a job.
     */
    @GetMapping("/{jobId}/artifacts")
    public ResponseEntity<ResearchDtos.ArtifactListResponse> listArtifacts(@PathVariable String jobId) {
        List<ResearchDtos.ArtifactInfo> artifacts = jobService.listArtifacts(CurrentUser.getUserId(), jobId);
        return ResponseEntity.ok(new ResearchDtos.ArtifactListResponse(artifacts));
    }

    /**
     * Download a specific artifact.
     */
    @GetMapping("/{jobId}/artifacts/{artifactId}")
    public ResponseEntity<org.springframework.core.io.Resource> downloadArtifact(
            @PathVariable String jobId,
            @PathVariable String artifactId) {
        byte[] artifact = jobService.getArtifact(CurrentUser.getUserId(), jobId, artifactId);
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }
        String filename = "artifact_" + jobId + "_" + System.currentTimeMillis() + ".json";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(new org.springframework.core.io.ByteArrayResource(artifact));
    }

    /**
     * List all jobs with optional status filter.
     */
    @GetMapping
    public ResponseEntity<com.stockbroker.research.api.ResearchDtos.JobListResponse> listJobs(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<com.stockbroker.research.api.ResearchDtos.JobSummary> jobs = jobService.listJobs(
                CurrentUser.getUserId(), status, limit, offset);
        return ResponseEntity.ok(new com.stockbroker.research.api.ResearchDtos.JobListResponse(jobs, null));
    }

    /**
     * Submit a walk-forward testing job.
     */
    @PostMapping("/walkforward")
    public ResponseEntity<com.stockbroker.research.api.ResearchDtos.JobResponse> submitWalkForward(
            @Valid @RequestBody com.stockbroker.research.api.ResearchDtos.WalkForwardRequest request) {
        String jobId = jobService.submitWalkForward(CurrentUser.getUserId(), request);
        return ResponseEntity.ok(new com.stockbroker.research.api.ResearchDtos.JobResponse(jobId, com.stockbroker.research.api.ResearchDtos.BacktestStatus.QUEUED, "Walk-forward job submitted"));
    }

    /**
     * Submit a parameter sensitivity analysis job.
     */
    @PostMapping("/sensitivity")
    public ResponseEntity<com.stockbroker.research.api.ResearchDtos.JobResponse> submitSensitivityAnalysis(
            @Valid @RequestBody com.stockbroker.research.api.ResearchDtos.SensitivityRequest request) {
        String jobId = jobService.submitSensitivityAnalysis(CurrentUser.getUserId(), request);
        return ResponseEntity.ok(new com.stockbroker.research.api.ResearchDtos.JobResponse(jobId, com.stockbroker.research.api.ResearchDtos.BacktestStatus.QUEUED, "Sensitivity analysis job submitted"));
    }

    /**
     * Compute deflated Sharpe ratio.
     */
    @PostMapping("/deflated-sharpe")
    public ResponseEntity<com.stockbroker.research.api.ResearchDtos.DeflatedSharpeResponse> computeDeflatedSharpe(
            @Valid @RequestBody com.stockbroker.research.api.ResearchDtos.DeflatedSharpeRequest request) {
        com.stockbroker.research.api.ResearchDtos.DeflatedSharpeResponse result = jobService.computeDeflatedSharpe(request);
        return ResponseEntity.ok(result);
    }
}
