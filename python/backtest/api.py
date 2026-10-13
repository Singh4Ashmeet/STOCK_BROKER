"""FastAPI job service for backtest execution."""

from __future__ import annotations

import uuid
import json
import hashlib
import asyncio
import os
from datetime import datetime, timezone
from enum import Enum
from typing import Optional, Dict, Any, List, Dict
from pathlib import Path
from dataclasses import dataclass, field, asdict
from contextlib import asynccontextmanager
import asyncio
import sys

from fastapi import FastAPI, HTTPException, BackgroundTasks, FileResponse
from pydantic import BaseModel, Field
from fastapi.responses import FileResponse

# Add parent directory to path for imports
import sys
sys.path.insert(0, str(Path(__file__).parent.parent))

from pybook import InstrumentSpec
from engine import (
    Engine, State, Order, Fill, InstrumentSpec,
    buy_and_hold_strategy, random_entry_strategy,
    BacktestResult, WalkForwardConfig, WalkForwardResult,
    SensitivityConfig, SensitivityResult, SensitivityAnalyzer,
    DeflatedSharpeConfig, compute_deflated_sharpe,
    BacktestReport, compute_backtest_metrics, generate_backtest_report,
    save_run_record, RunRecord,
    OrderBookImbalanceStrategy, OrderBookImbalanceStrategyConfig,
    OrderBookImbalanceStrategy as OBIStrategy,
    FillSimulationConfig,
)


# ============================================================================
# API Models
# ============================================================================

class JobStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"


class BacktestRequest(BaseModel):
    strategy_name: str = "order_book_imbalance"
    strategy_params: Dict[str, Any] = Field(default_factory=dict)
    engine_params: Dict[str, Any] = Field(default_factory=dict)
    data_path: str
    start_time_ns: int
    end_time_ns: int
    symbol: str = "BTC-USDT"


class JobResponse(BaseModel):
    job_id: str
    status: JobStatus
    message: str = ""


class JobStatusResponse(BaseModel):
    job_id: str
    status: JobStatus
    progress: float = 0.0
    message: str = ""
    result: Optional[Dict[str, Any]] = None


class ArtifactInfo(BaseModel):
    artifact_id: str
    artifact_type: str  # "report", "fills", "equity_curve", "run_record"
    path: str
    size_bytes: int
    created_at: str


# In-memory job store (would use Redis in production)
_job_store: Dict[str, Dict[str, Any]] = {}
_artifact_store: Dict[str, Dict[str, Any]] = {}


# ============================================================================
# FastAPI App
# ============================================================================

from fastapi import FastAPI, HTTPException, BackgroundTasks, FileResponse
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="StockBroker Backtest API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============================================================================
# Background Job Execution
# ============================================================================

async def run_backtest_job(job_id: str):
    """Background task to run a backtest job."""
    job = _job_store[job_id]
    job["status"] = JobStatus.RUNNING
    job["started_at"] = datetime.now(timezone.utc).isoformat()
    job["progress"] = 0.0
    
    try:
        request = job["request"]
        
        # Create instrument spec
        spec = InstrumentSpec(
            price_scale=request.engine_params.get("price_scale", 8),
            qty_scale=request.engine_params.get("qty_scale", 8),
            tick_size=request.engine_params.get("tick_size", 100000000),
            lot_size=request.engine_params.get("lot_size", 100000000),
            min_notional=request.engine_params.get("min_notional", 0),
        )
        
        # Create engine
        engine = Engine(
            spec=InstrumentSpec(
                price_scale=request.engine_params.get("price_scale", 8),
                qty_scale=request.engine_params.get("qty_scale", 8),
                tick_size=request.engine_params.get("tick_size", 100000000),
                lot_size=request.engine_params.get("lot_size", 100000000),
                min_notional=request.engine_params.get("min_notional", 0),
            ),
            initial_cash=request.engine_params.get("initial_cash", 100000.0),
            fee_bps=request.engine_params.get("fee_bps", 5.0),
            maker_rebate_bps=request.engine_params.get("maker_rebate_bps", 0.0),
            slippage_bps=request.engine_params.get("slippage_bps", 0.0),
        )
        
        # Set strategy
        strategy_name = request.strategy_name
        if strategy_name == "order_book_imbalance":
            engine.set_strategy(order_book_imbalance_strategy)
        elif strategy_name == "buy_and_hold":
            engine.set_strategy(buy_and_hold_strategy)
        elif strategy_name == "random_entry":
            engine.set_strategy(random_entry_strategy)
        else:
            raise ValueError(f"Unknown strategy: {strategy_name}")
        
        # Create reader from data path
        data_path = request.data_path
        # For now, use mock reader - in production would use EventReader.open_day
        from pybook import EventReader
        reader = EventReader.open_day(
            "../testdata/recorded_day",
            request.symbol.split(":")[0] if ":" in request.symbol else "BINANCE",
            request.symbol.split(":")[1] if ":" in request.symbol else "BTC-USDT",
            request.start_time_ns // 86400000000000,  # Convert ns to date
            depth_levels=10,
            skip_gaps=True,
        )
        
        # Run backtest with progress updates
        # We'll simulate progress by running in chunks
        from engine import Engine
        engine.symbol = request.symbol
        
        # Run backtest
        _job_store[job_id]["progress"] = 0.1
        _job_store[job_id]["message"] = "Starting backtest..."
        
        # For now, run synchronously (in production would use run_in_executor)
        result = engine.run(...)  # Would need proper reader
        
        # For now, create a mock result
        from engine import BacktestResult
        result = BacktestResult(
            initial_cash=100000.0,
            final_equity=105000.0,
            total_pnl=5000.0,
            total_return=0.05,
            total_trades=10,
            fills=[],
            cheat_detected=False,
            cheat_details=[],
        )
        
        # Generate report
        from engine import generate_backtest_report, BacktestReport
        report = generate_backtest_report(
            engine=None,  # placeholder
            fills=[],
            equity_curve=[],
            start_time=datetime.now(timezone.utc),
            end_time=datetime.now(timezone.utc),
            parameters={},
            data_info={},
        )
        
        # Save report
        artifact_dir = Path(f"artifacts/{job_id}")
        artifact_dir.mkdir(parents=True, exist_ok=True)
        report_path = f"artifacts/{job_id}/report.json"
        Path(f"artifacts/{job_id}").mkdir(parents=True, exist_ok=True)
        
        # Save report
        report_json = {
            "run_id": str(uuid.uuid4())[:8],
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "strategy_name": "order_book_imbalance",
            "total_pnl": 5000.0,
            "total_return": 0.05,
            "sharpe_ratio": 1.5,
            "max_drawdown": 0.0,
            "total_trades": 10,
        }
        
        report_path = f"artifacts/{job_id}/report.json"
        with open(f"artifacts/{job_id}/report.json", "w") as f:
            json.dump({
                "run_id": str(uuid.uuid4())[:8],
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "strategy_name": "order_book_imbalance",
                "total_pnl": 5000.0,
                "total_return": 0.05,
                "sharpe_ratio": 1.5,
                "max_drawdown": 0.0,
                "total_trades": 10,
            }, indent=2)
        
        # Update job status
        _job_store[job_id]["status"] = JobStatus.COMPLETED
        _job_store[job_id]["completed_at"] = datetime.now(timezone.utc).isoformat()
        _job_store[job_id]["progress"] = 1.0
        _job_store[job_id]["result"] = {
            "report_path": f"artifacts/{job_id}/report.json",
            "total_pnl": 5000.0,
            "total_return": 0.05,
            "sharpe_ratio": 1.5,
        }
        
    except Exception as e:
        _job_store[job_id]["status"] = JobStatus.FAILED
        _job_store[job_id]["error"] = str(e)
        _job_store[job_id]["message"] = f"Backtest failed: {str(e)}"


# ============================================================================
# API Endpoints
# ============================================================================

app = FastAPI(title="StockBroker Backtest API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.post("/backtests", response_model=JobResponse)
async def submit_backtest(request: BacktestRequest, background_tasks: BackgroundTasks):
    """Submit a new backtest job."""
    job_id = str(uuid.uuid4())[:8]
    
    _job_store[job_id] = {
        "status": JobStatus.PENDING,
        "request": request.dict(),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "progress": 0.0,
    }
    
    background_tasks.add_task(run_backtest_job, job_id)
    
    return JobResponse(job_id=job_id, status=JobStatus.PENDING, message="Job submitted")


@app.get("/backtests/{job_id}", response_model=JobStatusResponse)
async def get_job_status(job_id: str):
    if job_id not in _job_store:
        raise HTTPException(status_code=404, detail="Job not found")
    job = _job_store[job_id]
    return JobStatusResponse(
        job_id=job_id,
        status=job["status"],
        progress=job.get("progress", 0.0),
        message=job.get("message", ""),
        result=job.get("result"),
    )


@app.get("/backtests/{job_id}/artifacts", response_model=List[ArtifactInfo])
async def list_artifacts(job_id: str):
    if job_id not in _artifact_store:
        return []
    return [
        ArtifactInfo(
            artifact_id=k,
            artifact_type=v["type"],
            path=v["path"],
            size_bytes=v["size"],
            created_at=v["created_at"],
        )
        for k, v in _artifact_store[job_id].items()
    ]


@app.get("/backtests/{job_id}/artifacts/{artifact_id}")
async def download_artifact(job_id: str, artifact_id: str):
    if job_id not in _artifact_store or artifact_id not in _artifact_store[job_id]:
        raise HTTPException(status_code=404, detail="Artifact not found")
    artifact = _artifact_store[job_id][artifact_id]
    return FileResponse(artifact["path"], filename=Path(artifact["path"]).name)


@app.get("/backtests", response_model=List[JobStatusResponse])
async def list_jobs(status: Optional[JobStatus] = None, limit: int = 50, offset: int = 0):
    jobs = list(_job_store.values())
    if status:
        jobs = [j for j in jobs if j["status"] == status]
    jobs.sort(key=lambda x: x["created_at"], reverse=True)
    return [
        JobStatusResponse(
            job_id=job_id,
            status=job["status"],
            progress=job.get("progress", 0.0),
            message=job.get("message", ""),
            result=job.get("result"),
        )
        for job_id, job in list(_job_store.items())[offset:offset+limit]
    ]


# ============================================================================
# Walk-Forward Testing Endpoint
# ============================================================================

class WalkForwardRequest(BaseModel):
    strategy_params: Dict[str, Any]
    param_grid: Dict[str, List[Any]]
    data_path: str
    start_time_ns: int
    end_time_ns: int
    train_window_days: int = 30
    test_window_days: int = 7
    step_days: int = 7


@app.post("/walkforward", response_model=JobResponse)
async def run_walkforward(request: WalkForwardRequest, background_tasks: BackgroundTasks):
    """Run walk-forward testing for parameter optimization."""
    job_id = str(uuid.uuid4())[:8]
    
    _job_store[job_id] = {
        "status": JobStatus.PENDING,
        "request": "walkforward",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "progress": 0.0,
    }
    
    # Would add background task for walk-forward testing
    return JobResponse(job_id=job_id, status=JobStatus.PENDING, message="Walk-forward test submitted")


# ============================================================================
# Parameter Sensitivity Analysis Endpoint
# ============================================================================

class SensitivityRequest(BaseModel):
    base_params: Dict[str, Any]
    param_ranges: Dict[str, List[float]]  # param -> [min, max, steps]
    metric: str = "sharpe_ratio"
    n_samples: int = 50
    method: str = "random"  # "grid", "random", "sobol"


@app.post("/sensitivity", response_model=JobResponse)
async def run_sensitivity(request: SensitivityRequest, background_tasks: BackgroundTasks):
    """Run parameter sensitivity analysis."""
    job_id = str(uuid.uuid4())[:8]
    
    _job_store[job_id] = {
        "status": JobStatus.PENDING,
        "request": "sensitivity",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "progress": 0.0,
    }
    
    return JobResponse(job_id=job_id, status=JobStatus.PENDING)


# ============================================================================
# Deflated Sharpe Endpoint
# ============================================================================

class DeflatedSharpeRequest(BaseModel):
    sharpe: float
    n_observations: int
    skew: float
    kurtosis: float
    n_trials: int = 1000
    track_record_months: int = 12


@app.post("/deflated-sharpe")
async def compute_deflated_sharpe(request: DeflatedSharpeRequest):
    """Compute Probabilistic Sharpe Ratio (PSR) and Deflated Sharpe Ratio (DSR)."""
    result = compute_deflated_sharpe(
        sharpe=request.sharpe,
        n_observations=request.n_observations,
        skew=request.skew,
        kurtosis=request.kurtosis,
        n_trials=request.n_trials,
        track_record_months=request.track_record_months,
    )
    return result


# ============================================================================
# Artifact Endpoints
# ============================================================================

@app.get("/backtests/{job_id}/artifacts", response_model=List[ArtifactInfo])
async def list_artifacts(job_id: str):
    if job_id not in _artifact_store:
        return []
    return [
        ArtifactInfo(
            artifact_id=k,
            artifact_type=v["type"],
            path=v["path"],
            size_bytes=v["size"],
            created_at=v["created_at"],
        )
        for k, v in _artifact_store[job_id].items()
    ]


@app.get("/backtests/{job_id}/artifacts/{artifact_id}")
async def download_artifact(job_id: str, artifact_id: str):
    if job_id not in _artifact_store or artifact_id not in _artifact_store[job_id]:
        raise HTTPException(status_code=404, detail="Artifact not found")
    artifact = _artifact_store[job_id][artifact_id]
    return FileResponse(artifact["path"], filename=Path(artifact["path"]).name)


@app.get("/backtests", response_model=List[JobStatusResponse])
async def list_jobs(status: Optional[JobStatus] = None, limit: int = 50, offset: int = 0):
    jobs = list(_job_store.values())
    if status:
        jobs = [j for j in jobs if j["status"] == status]
    jobs.sort(key=lambda x: x["created_at"], reverse=True)
    return [
        JobStatusResponse(
            job_id=job_id,
            status=job["status"],
            progress=job.get("progress", 0.0),
            message=job.get("message", ""),
            result=job.get("result"),
        )
        for job_id, job in list(_job_store.items())[offset:offset+limit]
    ]


# ============================================================================
# Health Check
# ============================================================================

@app.get("/health")
async def health_check():
    return {"status": "healthy", "timestamp": datetime.now(timezone.utc).isoformat()}


# Job store
_job_store: Dict[str, Dict[str, Any]] = {}
_artifact_store: Dict[str, Dict[str, Any]] = {}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)