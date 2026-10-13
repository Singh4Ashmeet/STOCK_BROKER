//! Conversions between internal types and generated protobuf types.

use crate::broadcaster::{
    BroadcastDepthPoint, BroadcastLevel, BroadcastMetrics, BroadcastTiming, OutboundMsg,
};
use crate::proto;

/// Convert an `OutboundMsg::BookSnapshot` or `BookDelta` to a proto `BookUpdate`.
pub fn outbound_to_book_update(msg: &OutboundMsg) -> Option<proto::BookUpdate> {
    match msg {
        OutboundMsg::BookSnapshot {
            symbol,
            epoch,
            seq,
            bids,
            asks,
            timing,
        } => Some(proto::BookUpdate {
            symbol: symbol.clone(),
            epoch: *epoch,
            seq: *seq,
            timing: Some(broadcast_timing_to_proto(timing)),
            payload: Some(proto::book_update::Payload::Snapshot(proto::BookSnapshot {
                bids: levels_to_proto(bids),
                asks: levels_to_proto(asks),
            })),
        }),
        OutboundMsg::BookDelta {
            symbol,
            epoch,
            seq,
            bids,
            asks,
            timing,
        } => Some(proto::BookUpdate {
            symbol: symbol.clone(),
            epoch: *epoch,
            seq: *seq,
            timing: Some(broadcast_timing_to_proto(timing)),
            payload: Some(proto::book_update::Payload::Delta(proto::BookDelta {
                bids: levels_to_proto(bids),
                asks: levels_to_proto(asks),
            })),
        }),
        OutboundMsg::MetricsUpdate { .. } => None,
    }
}

/// Convert an `OutboundMsg::MetricsUpdate` to a proto `Metrics`.
pub fn outbound_to_metrics(msg: &OutboundMsg) -> Option<proto::Metrics> {
    match msg {
        OutboundMsg::MetricsUpdate {
            symbol,
            epoch,
            seq,
            timing,
            metrics,
        } => Some(proto::Metrics {
            symbol: symbol.clone(),
            epoch: *epoch,
            seq: *seq,
            timing: Some(broadcast_timing_to_proto(timing)),
            best_bid: metrics.best_bid,
            best_ask: metrics.best_ask,
            best_bid_qty: metrics.best_bid_qty,
            best_ask_qty: metrics.best_ask_qty,
            spread: metrics.spread,
            mid: metrics.mid,
            microprice: metrics.microprice,
            imbalance_top_n: metrics.imbalance_top_n,
            rolling_vwap: metrics.rolling_vwap,
            trade_flow_imbalance: metrics.trade_flow_imbalance,
            depth: depth_points_to_proto(&metrics.depth),
            book_valid: metrics.book_valid,
        }),
        _ => None,
    }
}

/// Convert broadcast levels to proto levels.
fn levels_to_proto(levels: &[BroadcastLevel]) -> Vec<proto::Level> {
    levels
        .iter()
        .map(|l| proto::Level {
            price: l.price,
            qty: l.qty,
        })
        .collect()
}

/// Convert broadcast timing to proto timing.
fn broadcast_timing_to_proto(t: &BroadcastTiming) -> proto::Timing {
    proto::Timing {
        exchange_ts_ns: t.exchange_ts_ns,
        recv_ts_ns: t.recv_ts_ns,
        publish_ts_ns: t.publish_ts_ns,
    }
}

/// Convert depth points to proto.
fn depth_points_to_proto(points: &[BroadcastDepthPoint]) -> Vec<proto::DepthPoint> {
    points
        .iter()
        .map(|p| proto::DepthPoint {
            levels: p.levels,
            bid_qty: p.bid_qty,
            ask_qty: p.ask_qty,
        })
        .collect()
}

/// Build a `BroadcastMetrics` from a `MetricsSnapshot` and symbol state.
pub fn metrics_snapshot_to_broadcast(
    snap: &sb_metrics::MetricsSnapshot,
    book_valid: bool,
    depth_levels: usize,
) -> BroadcastMetrics {
    BroadcastMetrics {
        best_bid: snap.bbo.best_bid.map_or(0, |p| p.units()),
        best_ask: snap.bbo.best_ask.map_or(0, |p| p.units()),
        best_bid_qty: snap.bbo.best_bid_qty.map_or(0, |q| q.units()),
        best_ask_qty: snap.bbo.best_ask_qty.map_or(0, |q| q.units()),
        spread: snap.bbo.spread_units,
        mid: snap.bbo.mid,
        microprice: snap.bbo.microprice,
        imbalance_top_n: snap.depth.imbalance,
        rolling_vwap: snap.vwap.vwap,
        trade_flow_imbalance: snap.trade_flow.imbalance,
        depth: vec![BroadcastDepthPoint {
            levels: depth_levels.min(u32::MAX as usize) as u32,
            bid_qty: snap.depth.bid_depth.units(),
            ask_qty: snap.depth.ask_depth.units(),
        }],
        book_valid,
    }
}

/// Convert internal `Level` to `BroadcastLevel`.
pub fn level_to_broadcast(level: &sb_core::Level) -> BroadcastLevel {
    BroadcastLevel {
        price: level.price.units(),
        qty: level.qty.units(),
    }
}

/// Convert a slice of `Level` to `Vec<BroadcastLevel>`.
pub fn levels_to_broadcast(levels: &[sb_core::Level]) -> Vec<BroadcastLevel> {
    levels.iter().map(level_to_broadcast).collect()
}
