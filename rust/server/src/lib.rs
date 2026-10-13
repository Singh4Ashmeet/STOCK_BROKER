//! sb-server: gRPC MarketData service with conflation and fan-out.
//!
//! Implements the `MarketData` service defined in `proto/stockbroker/market/v1/market.proto`.
//! Late joiners receive a snapshot then deltas with epoch/seq. Each subscriber has a bounded
//! queue that conflates to the latest snapshot when it falls behind, so a slow client never
//! slows the feed.
//!
//! # Architecture
//!
//! ```text
//! Feed source ──> SymbolState (book + metrics + epoch/seq)
//!                       │
//!                       ▼
//!                 Broadcaster ──> [SubQueue₁] ──> gRPC stream₁
//!                             ──> [SubQueue₂] ──> gRPC stream₂
//!                             ──> [SubQueueₙ] ──> gRPC streamₙ
//! ```
//!
//! # Configuration
//!
//! The server reads a TOML config file. See [`config::ServerConfig`] for all tunables.

#![allow(clippy::pedantic)]
#![allow(clippy::cast_precision_loss)]
#![allow(clippy::cast_sign_loss)]
#![allow(clippy::cast_possible_truncation)]
#![allow(clippy::too_many_arguments)]
#![allow(clippy::expect_used)]
#![allow(clippy::unwrap_used)]
#![allow(clippy::missing_panics_doc)]
#![allow(clippy::missing_errors_doc)]
#![allow(clippy::module_name_repetitions)]
#![allow(clippy::items_after_statements)]
#![allow(clippy::similar_names)]

pub mod broadcaster;
pub mod config;
pub mod convert;
pub mod metrics;
pub mod service;
pub mod state;

/// Generated protobuf/gRPC code (from build.rs / tonic-build).
#[allow(clippy::all, clippy::pedantic, clippy::restriction, clippy::nursery)]
pub mod proto {
    tonic::include_proto!("stockbroker.market.v1");
}
