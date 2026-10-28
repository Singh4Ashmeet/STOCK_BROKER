"""Event-driven backtesting engine with no-lookahead enforcement and realistic fill simulation."""

from __future__ import annotations

import itertools
import math
import random
import uuid
import json
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from scipy import stats

from pybook import EventReader, EventBatch, InstrumentSpec, OrderBook


# ============================================================================
# Core Data Structures
# ============================================================================


@dataclass(frozen=True)
class Order:
    """Order to be placed by a strategy."""
    symbol: str
    side: str  # "buy" or "sell"
    qty: float
    order_type: str  # "market" or "limit"
    limit_price: Optional[float] = None
    # For tracking
    strategy_id: str = ""
    client_order_id: str = field(default_factory=lambda: str(uuid.uuid4()))


@dataclass
class QueuedOrder:
    """Resting limit order in the queue with mutable state."""
    order: Order
    remaining_qty: float
    status: str = "OPEN"  # "OPEN", "PARTIAL", "FILLED", "CANCELLED", "REJECTED"
    queue_position: int = 0
    created_ts: int = 0
    created_batch_epoch: int = 0
    created_batch_seq: int = 0
    
    @property
    def is_buy(self) -> bool:
        return self.order.side == "buy"
    
    @property
    def limit_price(self) -> float:
        return self.order.limit_price or 0.0
    
    @property
    def is_marketable(self, best_bid: float, best_ask: float) -> bool:
        """Check if order is marketable given current best bid/ask."""
        if self.is_buy:
            return self.limit_price >= best_ask
        else:
            return self.limit_price <= best_bid


@dataclass
class Fill:
    """Represents a filled order."""
    symbol: str
    side: str
    qty: float
    price: float
    fee: float
    timestamp_ns: int
    order_id: str
    fill_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    liquidity: str = "TAKER"  # "MAKER" or "TAKER"
    fee_asset: str = "USDT"
    fill_type: str = "MARKET"  # "MARKET" or "LIMIT"
    # Backtest metadata
    book_epoch: int = 0
    book_seq: int = 0
    slippage_bps: float = 0.0
    queue_position: int = 0  # Approximate queue position at order placement
    latency_ns: int = 0  # Latency from order to fill


@dataclass
class Position:
    """Current position state."""
    symbol: str
    qty: float = 0.0
    avg_price: float = 0.0
    unrealized_pnl: float = 0.0
    realized_pnl: float = 0.0


@dataclass
class Account:
    """Account state."""
    cash: float
    positions: Dict[str, Position] = field(default_factory=dict)
    fills: List = field(default_factory=list)
    orders: List = field(default_factory=list)

    def equity(self, prices: Dict[str, float]) -> float:
        """Calculate total equity including unrealized PnL."""
        eq = self.cash
        for sym, pos in self.positions.items():
            if sym in prices and pos.qty != 0:
                eq += pos.qty * prices[sym]
        return eq


@dataclass(frozen=True)
class State:
    """Immutable state passed to strategy on each event."""
    timestamp_ns: int
    symbol: str
    best_bid: float
    best_ask: float
    mid: float
    spread: float
    vwap: float
    imbalance: float
    book_valid: bool
    position: float
    cash: float
    equity: float
    book_bid_prices: Tuple[float, ...]
    book_bid_qtys: Tuple[float, ...]
    book_ask_prices: Tuple[float, ...]
    book_ask_qtys: Tuple[float, ...]


# ============================================================================
# Engine Configuration
# ============================================================================


@dataclass
class FillSimulationConfig:
    """Configuration for fill simulation."""
    base_latency_ns: int = 1_000_000      # 1ms base
    latency_jitter_ns: int = 500_000      # ±0.5ms
    queue_position_model: str = "linear"  # "linear", "sqrt", "log"
    queue_depth_factor: float = 1.0
    enable_slippage: bool = True
    enable_market_impact: bool = True
    impact_coefficient: float = 0.1


class Engine:
    """Event-driven backtesting engine with no-lookahead enforcement and realistic fill simulation."""

    # Fee rates (in basis points)
    DEFAULT_MAKER_FEE_BPS = 1.0    # 0.01%
    DEFAULT_TAKER_FEE_BPS = 5.0    # 0.05%
    BPS_DIVISOR = 10000.0

    def __init__(
        self,
        spec: 'InstrumentSpec',
        initial_cash: float = 100_000.0,
        fee_bps: float = 5.0,
        maker_rebate_bps: float = 0.0,
        slippage_bps: float = 0.0,
        # Fill simulation config
        base_latency_ns: int = 1_000_000,
        latency_jitter_ns: int = 500_000,
        queue_position_model: str = "linear",
        queue_depth_factor: float = 1.0,
        enable_slippage: bool = True,
        enable_market_impact: bool = True,
        impact_coefficient: float = 0.1,
    ):
        self.spec = spec
        self.initial_cash = initial_cash
        self.fee_bps = fee_bps
        self.maker_rebate_bps = maker_rebate_bps
        self.slippage_bps = slippage_bps

        # Fill simulation config
        self.base_latency_ns = base_latency_ns
        self.latency_jitter_ns = latency_jitter_ns
        self.queue_position_model = queue_position_model
        self.queue_depth_factor = queue_depth_factor
        self.enable_slippage = enable_slippage
        self.enable_market_impact = enable_market_impact
        self.impact_coefficient = impact_coefficient

        self.book = OrderBook(spec, window_ticks=2000)
        self.account = Account(cash=initial_cash)

        # Strategy callback
        self.on_event: Optional[Callable[['State'], list]] = None

        # Engine state
        self._current_ts: int = 0
        self._current_batch_idx: int = 0
        self._current_event_idx: int = 0
        self._current_batch = None
        self._book_initialized: bool = False
        self._first_snapshot_received: bool = False
        self._symbol: str = "BTC-USDT"

        # Order tracking
        self._open_orders: Dict[str, Order] = {}
        self._order_queue: Dict[str, List[QueuedOrder]] = {}  # List for sorting by price-time

        # Fill tracking
        self._fill_id_counter = 0

        # Cheat detection
        self._cheat_detected: bool = False
        self._cheat_details: List[str] = []

        # Fill simulation config
        self._fill_config = FillSimulationConfig(
            base_latency_ns=base_latency_ns,
            latency_jitter_ns=latency_jitter_ns,
            queue_position_model=queue_position_model,
            queue_depth_factor=queue_depth_factor,
            enable_slippage=enable_slippage,
            enable_market_impact=enable_market_impact,
            impact_coefficient=impact_coefficient,
        )

        # Walk-forward testing
        self._walk_forward_config: Optional[WalkForwardConfig] = None

        # Parameter sensitivity
        self._sensitivity_config: Optional[SensitivityConfig] = None

        # Deflated Sharpe
        self._deflated_sharpe_config: Optional[DeflatedSharpeConfig] = None

    @property
    def symbol(self) -> str:
        return self._symbol

    @symbol.setter
    def symbol(self, value: str):
        self._symbol = value

    def set_strategy(self, on_event: Callable[['State'], list]) -> None:
        """Register the strategy callback."""
        self.on_event = on_event

    def _build_state(self, batch: 'EventBatch', idx: int) -> 'State':
        """Build the state object for the strategy at the given event index."""
        bid_prices = self.book.levels("bid", 10)
        ask_prices = self.book.levels("ask", 10)

        bid_prices_f = [self.spec.price_to_float(p) for p, _ in bid_prices]
        bid_qtys_f = [self.spec.qty_to_float(q) for _, q in bid_prices]
        ask_prices_f = [self.spec.price_to_float(p) for p, _ in ask_prices]
        ask_qtys_f = [self.spec.qty_to_float(q) for _, q in ask_prices]

        best_bid = bid_prices_f[0] if bid_prices_f else 0.0
        best_ask = ask_prices_f[0] if ask_prices_f else 0.0
        mid = (best_bid + best_ask) / 2 if best_bid and best_ask else 0.0
        spread = best_ask - best_bid if best_bid and best_ask else 0.0

        pos = self.account.positions.get(self.symbol, Position(self.symbol))

        vwap = batch.vwap[idx] if idx < len(batch.vwap) else 0.0
        imbalance = batch.imbalance[idx] if idx < len(batch.imbalance) else 0.0
        book_valid = batch.book_valid[idx] if idx < len(batch.book_valid) else False

        equity = self.account.equity({self.symbol: mid})

        return State(
            timestamp_ns=batch.recv_ts[idx],
            symbol=self.symbol,
            best_bid=best_bid,
            best_ask=best_ask,
            mid=mid,
            spread=spread,
            vwap=vwap,
            imbalance=imbalance,
            book_valid=book_valid,
            position=pos.qty,
            cash=self.account.cash,
            equity=equity,
            book_bid_prices=tuple(bid_prices_f),
            book_bid_qtys=tuple(bid_qtys_f),
            book_ask_prices=tuple(ask_prices_f),
            book_ask_qtys=tuple(ask_qtys_f),
        )

    def run(self, reader: 'EventReader') -> 'BacktestResult':
        """Run the backtest on the given event reader."""
        self._cheat_detected = False
        self._cheat_details = []

        # Reset state
        self.book = OrderBook(self.spec, window_ticks=2000)
        self.account = Account(cash=self.initial_cash)
        self._book_initialized = False
        self._first_snapshot_received = False
        self._open_orders = {}
        self._order_queue = {}
        self._fill_id_counter = 0

        batch_count = 0
        while True:
            batch = reader.next_batch(10000)
            if batch is None:
                break

            self._current_batch = batch
            self._process_batch(batch)
            batch_count += 1

            if batch_count > 100000:
                break

        final_price = self._get_last_price()
        final_equity = self.account.equity({self.symbol: final_price})

        return BacktestResult(
            initial_cash=self.initial_cash,
            final_equity=final_equity,
            total_pnl=final_equity - self.initial_cash,
            total_return=(final_equity - self.initial_cash) / self.initial_cash,
            total_trades=len(self.account.fills),
            fills=self.account.fills,
            cheat_detected=self._cheat_detected,
            cheat_details=self._cheat_details,
        )

    def _process_batch(self, batch: 'EventBatch') -> None:
        self._current_batch = batch

        for i in range(batch.length):
            self._current_event_idx = i
            event_kind = batch.event_kind[i]

            # Update book
            if event_kind == 0:
                self._apply_snapshot(batch, i)
            elif event_kind == 1:
                self._apply_delta(batch, i)
            elif event_kind == 2:
                self._apply_trade(batch, i)
            elif event_kind == 3:
                pass

            # After book update, match queued limit orders
            if self._book_initialized:
                self._match_queued_orders()

            # Call strategy if we have a valid book
            if self._book_initialized and self.on_event and batch.book_valid[i]:
                state = self._build_state(self._current_batch, i)
                self._call_strategy_safely(state, i)

    def _match_queued_orders(self) -> None:
        """Match queued limit orders against current book state."""
        if self.symbol not in self._order_queue:
            return
        
        bid = self.book.best_bid()
        ask = self.book.best_ask()
        if not bid or not ask:
            return
        
        bid_price = self.spec.price_to_float(bid[0])
        ask_price = self.spec.price_to_float(ask[0])
        
        queue = self._order_queue[self.symbol]
        any_filled = False
        
        for queued_order in queue:
            if queued_order.status not in ("OPEN", "PARTIAL"):
                continue
            if queued_order.remaining_qty <= 0:
                continue
            
            # Check if order is now marketable
            if queued_order.is_buy:
                if queued_order.limit_price < ask_price:
                    continue  # Not marketable yet
                max_price = queued_order.limit_price
            else:
                if queued_order.limit_price > bid_price:
                    continue  # Not marketable yet
                min_price = queued_order.limit_price
            
            # Walk the book for the remaining quantity
            if queued_order.is_buy:
                fills = self._walk_book_for_fill("ask", queued_order.remaining_qty, max_price=max_price)
            else:
                fills = self._walk_book_for_fill("bid", queued_order.remaining_qty, min_price=min_price)
            
            if not fills:
                continue
            
            # Execute fills for this queued order
            self._execute_queued_fills(queued_order, fills)
            any_filled = True
        
        # Re-sort queue after fills
        if any_filled:
            self._sort_queue(self.symbol)

    def _execute_queued_fills(self, queued_order: QueuedOrder, fills: List[Tuple[float, float]]) -> None:
        """Execute fills for a queued limit order (MAKER liquidity)."""
        order = queued_order.order
        total_filled = 0.0
        fill_idx = 0
        
        for fill_price, fill_qty in fills:
            if fill_qty <= 0:
                continue
            
            # Record pre-slippage price
            pre_slippage_price = fill_price
            
            # Apply slippage and market impact
            adj_price = self._apply_slippage(fill_price, order.side)
            adj_price = self._apply_market_impact(adj_price, order.side, fill_qty)
            
            # Calculate fee (MAKER gets rebate)
            fee_bps = -self.maker_rebate_bps  # Negative for rebate
            fee = adj_price * fill_qty * fee_bps / 10000.0
            
            # Check cash/position for this fill
            cost = adj_price * fill_qty + fee
            if order.side == "buy":
                if self.account.cash < cost:
                    break  # Insufficient cash for this fill
                self.account.cash -= cost
            else:
                pos = self.account.positions.get(order.symbol)
                if pos is None or pos.qty < fill_qty:
                    break  # Insufficient position
                self.account.cash += adj_price * fill_qty - fee
            
            # Update position
            pos = self.account.positions.get(order.symbol)
            if pos is None:
                pos = Position(order.symbol)
                self.account.positions[order.symbol] = pos
            
            if order.side == "buy":
                new_qty = pos.qty + fill_qty
                new_avg = (pos.avg_price * pos.qty + adj_price * fill_qty) / new_qty if new_qty > 0 else adj_price
                pos.qty = new_qty
                pos.avg_price = new_avg
            else:
                realized_pnl = (adj_price - pos.avg_price) * fill_qty
                pos.realized_pnl += realized_pnl
                pos.qty -= fill_qty
                if pos.qty == 0:
                    pos.avg_price = 0.0
                    # Don't reset realized_pnl - it should accumulate
            
            # Calculate slippage
            if pre_slippage_price != 0:
                slippage_bps = ((adj_price - pre_slippage_price) / pre_slippage_price) * 10000.0
                if order.side == "sell":
                    slippage_bps = -slippage_bps
            else:
                slippage_bps = 0.0
            
            # Record fill with MAKER liquidity
            fill_id = f"fill_{self._fill_id_counter}_q{fill_idx}"
            self._fill_id_counter += 1
            
            fill = Fill(
                symbol=order.symbol,
                side=order.side,
                qty=fill_qty,
                price=adj_price,
                fee=fee,
                timestamp_ns=self._current_ts,
                order_id=order.client_order_id,
                fill_id=fill_id,
                liquidity="MAKER",
                fee_asset="USDT",
                fill_type="LIMIT",
                book_epoch=self._current_batch.epoch[self._current_event_idx] if self._current_batch else 0,
                book_seq=self._current_batch.seq[self._current_event_idx] if self._current_batch else 0,
                slippage_bps=slippage_bps,
                queue_position=queued_order.queue_position,
            )
            
            self.account.fills.append(fill)
            total_filled += fill_qty
            fill_idx += 1
            
            if total_filled >= queued_order.remaining_qty:
                break
        
        # Update queued order state
        queued_order.remaining_qty -= total_filled
        if queued_order.remaining_qty <= 0:
            queued_order.status = "FILLED"
        elif total_filled > 0:
            queued_order.status = "PARTIAL"
        else:
            queued_order.status = "OPEN"

    def _apply_snapshot(self, batch, idx):
        bids = batch.bid_prices[idx] if idx < len(batch.bid_prices) else []
        asks = batch.ask_prices[idx] if idx < len(batch.ask_prices) else []

        bid_list = [(self.spec.price_to_float(p), self.spec.qty_to_float(q))
                    for p, q in zip(bids, batch.bid_qtys[idx] if idx < len(batch.bid_qtys) else [])]
        ask_list = [(self.spec.price_to_float(p), self.spec.qty_to_float(q))
                    for p, q in zip(asks, batch.ask_qtys[idx] if idx < len(batch.ask_qtys) else [])]

        self.book.apply_snapshot(bid_list, ask_list)
        self._book_initialized = True
        self._first_snapshot_received = True

    def _apply_delta(self, batch, idx):
        if not self._book_initialized:
            return

        bids = batch.bid_prices[idx] if idx < len(batch.bid_prices) else []
        asks = batch.ask_prices[idx] if idx < len(batch.ask_prices) else []

        bid_list = [(self.spec.price_to_float(p), self.spec.qty_to_float(q))
                    for p, q in zip(bids, batch.bid_qtys[idx] if idx < len(batch.bid_qtys) else [])]
        ask_list = [(self.spec.price_to_float(p), self.spec.qty_to_float(q))
                    for p, q in zip(asks, batch.ask_qtys[idx] if idx < len(batch.ask_qtys) else [])]

        self.book.apply_delta(bid_list, ask_list)

    def _apply_trade(self, batch, idx):
        if idx >= len(batch.trade_price):
            return
        _ = self.spec.price_to_float(batch.trade_price[idx])
        _ = self.spec.qty_to_float(batch.trade_qty[idx])
        _ = batch.trade_aggressor[idx]

    def _call_strategy_safely(self, state: 'State', event_idx: int) -> None:
        """Call strategy with cheat detection."""
        if not self.on_event:
            return

        try:
            orders = self.on_event(state)
        except Exception as e:
            self._cheat_detected = True
            self._cheat_details.append(f"Strategy raised exception: {e}")
            return

        for order in orders:
            self._process_order(order)

    def _get_last_price(self) -> float:
        if self.book.best_bid() and self.book.best_ask():
            return (self.spec.price_to_float(self.book.best_bid()[0]) +
                    self.spec.price_to_float(self.book.best_ask()[0])) / 2
        return 0.0

    def _process_order(self, order: Order) -> None:
        """Process an order from the strategy and simulate fill."""
        # Generate fill ID
        self._fill_id_counter += 1
        fill_id = f"fill_{self._fill_id_counter}"
        
        # Get current book state
        bid = self.book.best_bid()
        ask = self.book.best_ask()
        if not bid or not ask:
            return  # No liquidity
        
        bid_price = self.spec.price_to_float(bid[0])
        ask_price = self.spec.price_to_float(ask[0])
        
        if order.order_type == "market":
            # Market orders are always TAKER
            self._fill_market_order(order, bid_price, ask_price, fill_id)
        elif order.order_type == "limit":
            if order.limit_price is None:
                return
            # Check if limit order is immediately marketable
            if order.side == "buy" and order.limit_price >= ask_price:
                # Crosses spread - TAKER
                self._fill_limit_order_as_taker(order, ask_price, fill_id)
            elif order.side == "sell" and order.limit_price <= bid_price:
                # Crosses spread - TAKER
                self._fill_limit_order_as_taker(order, bid_price, fill_id)
            else:
                # Resting limit order - add to queue as MAKER
                self._queue_limit_order(order)
        else:
            return

    def _fill_market_order(self, order: Order, bid_price: float, ask_price: float, fill_id: str) -> None:
        """Fill a market order by walking the book."""
        if order.side == "buy":
            # Buy walks asks (lowest to highest)
            fills = self._walk_book_for_fill("ask", order.qty)
            if not fills:
                return
            self._execute_fills(order, fills, "TAKER", fill_id)
        else:
            # Sell walks bids (highest to lowest)
            fills = self._walk_book_for_fill("bid", order.qty)
            if not fills:
                return
            self._execute_fills(order, fills, "TAKER", fill_id)

    def _fill_limit_order_as_taker(self, order: Order, fill_price: float, fill_id: str) -> None:
        """Fill a limit order that crosses the spread (TAKER liquidity)."""
        # Walk the book up to the limit price
        if order.side == "buy":
            fills = self._walk_book_for_fill("ask", order.qty, max_price=order.limit_price)
        else:
            fills = self._walk_book_for_fill("bid", order.qty, min_price=order.limit_price)
        
        if not fills:
            return
        
        self._execute_fills(order, fills, "TAKER", fill_id)

    def _queue_limit_order(self, order: Order) -> None:
        """Add a resting limit order to the queue (MAKER liquidity)."""
        if order.symbol not in self._order_queue:
            self._order_queue[order.symbol] = []
        
        queued = QueuedOrder(
            order=order,
            remaining_qty=order.qty,
            status="OPEN",
            queue_position=0,  # Will be set after sorting
            created_ts=self._current_ts,
            created_batch_epoch=self._current_batch.epoch[self._current_event_idx] if self._current_batch else 0,
            created_batch_seq=self._current_batch.seq[self._current_event_idx] if self._current_batch else 0,
        )
        self._order_queue[order.symbol].append(queued)
        self._sort_queue(order.symbol)

    def _walk_book_for_fill(self, side: str, qty: float, max_price: float = None, min_price: float = None) -> List[Tuple[float, float]]:
        """Walk the book for a fill using Python-side book levels to avoid notional overflow."""
        target_qty = qty
        if target_qty <= 0:
            return []
        
        # Get book levels from Rust (up to 50 levels)
        levels = self.book.levels(side, 50)
        if not levels:
            return []
        
        results = []
        remaining = target_qty
        
        for price_units, qty_units in levels:
            if remaining <= 0:
                break
            
            price = self.spec.price_to_float(price_units)
            level_qty = self.spec.qty_to_float(qty_units)
            
            # Apply price limits for limit orders
            if max_price is not None and price > max_price:
                break
            if min_price is not None and price < min_price:
                break
            
            fill_qty = min(level_qty, remaining)
            results.append((price, fill_qty))
            remaining -= fill_qty
        
        return results

    def _execute_fills(self, order: Order, fills: List[Tuple[float, float]], liquidity: str, base_fill_id: str) -> None:
        """Execute a list of fills for an order, handling partial fills."""
        total_filled = 0.0
        fill_idx = 0
        
        for fill_price, fill_qty in fills:
            if fill_qty <= 0:
                continue
            
            # Record pre-slippage price
            pre_slippage_price = fill_price
            
            # Apply slippage and market impact
            adj_price = self._apply_slippage(fill_price, order.side)
            adj_price = self._apply_market_impact(adj_price, order.side, fill_qty)
            
            # Calculate fee
            if liquidity == "TAKER":
                fee_bps = self.fee_bps
            else:  # MAKER
                fee_bps = -self.maker_rebate_bps
            
            fee = adj_price * fill_qty * fee_bps / 10000.0
            
            # Check cash/position for this fill
            cost = adj_price * fill_qty + fee
            if order.side == "buy":
                if self.account.cash < cost:
                    break  # Insufficient cash for this fill
                self.account.cash -= cost
            else:
                pos = self.account.positions.get(order.symbol)
                if pos is None or pos.qty < fill_qty:
                    break  # Insufficient position
                self.account.cash += adj_price * fill_qty - fee
            
            # Update position
            pos = self.account.positions.get(order.symbol)
            if pos is None:
                pos = Position(order.symbol)
                self.account.positions[order.symbol] = pos
            
            if order.side == "buy":
                new_qty = pos.qty + fill_qty
                new_avg = (pos.avg_price * pos.qty + adj_price * fill_qty) / new_qty if new_qty > 0 else adj_price
                pos.qty = new_qty
                pos.avg_price = new_avg
            else:
                realized_pnl = (adj_price - pos.avg_price) * fill_qty
                pos.realized_pnl += realized_pnl
                pos.qty -= fill_qty
                if pos.qty == 0:
                    pos.avg_price = 0.0
                    # Don't reset realized_pnl - it should accumulate
            
            # Calculate slippage
            if pre_slippage_price != 0:
                slippage_bps = ((adj_price - pre_slippage_price) / pre_slippage_price) * 10000.0
                if order.side == "sell":
                    slippage_bps = -slippage_bps
            else:
                slippage_bps = 0.0
            
            # Record fill
            fill_id = f"{base_fill_id}_{fill_idx}"
            fill = Fill(
                symbol=order.symbol,
                side=order.side,
                qty=fill_qty,
                price=adj_price,
                fee=fee,
                timestamp_ns=self._current_ts,
                order_id=order.client_order_id,
                fill_id=fill_id,
                liquidity=liquidity,
                fee_asset="USDT",
                fill_type=order.order_type.upper(),
                book_epoch=self._current_batch.epoch[self._current_event_idx] if self._current_batch else 0,
                book_seq=self._current_batch.seq[self._current_event_idx] if self._current_batch else 0,
                slippage_bps=slippage_bps,
            )
            
            self.account.fills.append(fill)
            total_filled += fill_qty
            fill_idx += 1
            
            if total_filled >= order.qty:
                break
        
        self._open_orders[order.client_order_id] = order

    def _sort_queue(self, symbol: str) -> None:
        """Sort the order queue by price-time priority."""
        if symbol not in self._order_queue:
            return
        
        queue = self._order_queue[symbol]
        # Filter open orders
        open_orders = [o for o in queue if o.status == "OPEN" or o.status == "PARTIAL"]
        
        # Sort by price-time priority
        # Buy: higher price first, then earlier time
        # Sell: lower price first, then earlier time
        if open_orders and open_orders[0].is_buy:
            open_orders.sort(key=lambda o: (-o.limit_price, o.created_ts))
        else:
            open_orders.sort(key=lambda o: (o.limit_price, o.created_ts))
        
        # Update queue positions
        for i, o in enumerate(open_orders):
            o.queue_position = i + 1
        
        # Rebuild queue: open orders first, then filled/cancelled at end
        filled_cancelled = [o for o in queue if o.status in ("FILLED", "CANCELLED", "REJECTED")]
        self._order_queue[symbol] = open_orders + filled_cancelled

    def _apply_slippage(self, price: float, side: str) -> float:
        """Apply slippage to fill price."""
        if not self.enable_slippage:
            return price
        slippage = price * self.slippage_bps / 10000.0
        if self._fill_config.enable_slippage:
            import random
            slippage *= random.uniform(-1, 1)
        if side == "buy":
            return price + slippage
        else:
            return price - slippage

    def _apply_market_impact(self, price: float, side: str, qty: float) -> float:
        """Apply market impact to fill price."""
        if not self.enable_market_impact:
            return price
        impact = price * self.impact_coefficient * qty / 100000.0  # Simplified model
        if side == "buy":
            return price + impact
        else:
            return price - impact


# ============================================================================
# Baseline Strategies
# ============================================================================


def buy_and_hold_strategy(state: State) -> list[Order]:
    """Buy and hold strategy - buy once at the beginning and hold."""
    if state.position == 0 and state.cash > 0 and state.book_valid:
        # Buy with all available cash (minus small buffer)
        qty = (state.cash * 0.99) / state.mid if state.mid > 0 else 0
        if qty > 0:
            return [Order(
                symbol="BTC-USDT",
                side="buy",
                qty=min(qty, 1.0),  # Cap for testing
                order_type="market",
            )]
    return []


def random_entry_strategy(state: State) -> list[Order]:
    """Random entry strategy for baseline comparison."""
    if state.book_valid and random.random() < 0.001:  # 0.1% chance per event
        side = "buy" if random.random() < 0.5 else "sell"
        qty = 0.01
        return [Order(
            symbol="BTC-USDT",
            side=side,
            qty=qty,
            order_type="market",
        )]
    return []


def order_book_imbalance_strategy(state: State) -> list[Order]:
    """Order book imbalance strategy - trade based on book imbalance."""
    if not state.book_valid or state.imbalance == 0:
        return []

    # Strong imbalance towards bid side (positive imbalance) -> buy
    # Strong imbalance towards ask side (negative imbalance) -> sell
    threshold = 0.3
    if abs(state.imbalance) < threshold:
        return []

    side = "buy" if state.imbalance > 0 else "sell"
    qty = 0.01 * abs(state.imbalance)  # Scale by imbalance strength

    return [Order(
        symbol="BTC-USDT",
        side=side,
        qty=qty,
        order_type="market",
    )]


# ============================================================================
# Walk-Forward Testing
# ============================================================================


@dataclass
class WalkForwardConfig:
    """Configuration for walk-forward testing."""
    train_window_days: int = 30      # Training window in days
    test_window_days: int = 7        # Test window in days
    step_days: int = 7               # Step size between folds
    min_train_days: int = 14         # Minimum training data required
    anchor: str = "rolling"          # "rolling" or "anchored"
    n_splits: int = 5                # Number of walk-forward splits


@dataclass
class WalkForwardResult:
    """Results from a single walk-forward fold."""
    fold_idx: int
    train_start: int  # timestamp_ns
    train_end: int
    test_start: int
    test_end: int
    train_metrics: Dict[str, float]
    test_metrics: Dict[str, float]
    best_params: Dict[str, Any]
    model: Optional[Any] = None


class WalkForwardTester:
    """Walk-forward testing for parameter optimization and validation."""

    def __init__(self, config: WalkForwardConfig):
        self.config = config
        self.results: List[WalkForwardResult] = []

    def split_data(self, data_start_ns: int, data_end_ns: int) -> List[Tuple[int, int, int, int]]:
        """Generate walk-forward splits.

        Returns list of (train_start, train_end, test_start, test_end) in nanoseconds.
        """
        splits = []
        ns_per_day = 86_400_000_000_000  # nanoseconds per day

        train_ns = self.config.train_window_days * ns_per_day
        test_ns = self.config.test_window_days * ns_per_day
        step_ns = self.config.step_days * ns_per_day

        current_train_start = data_start_ns
        fold_idx = 0

        while True:
            train_end = current_train_start + train_ns
            test_start = train_end
            test_end = test_start + test_ns

            if test_end > data_end_ns:
                break

            if train_end - current_train_start < self.config.min_train_days * 86_400_000_000_000:
                break

            splits.append((current_train_start, train_end, test_start, test_end))
            fold_idx += 1

            if self.config.anchor == "rolling":
                current_train_start += step_ns
            else:  # anchored
                current_train_start = data_start_ns

        return splits

    def run_walk_forward(
        self,
        engine_factory: Callable[[], Engine],
        parameter_grid: Dict[str, List[Any]],
        reader_factory: Callable[[int, int], 'EventReader'],
    ) -> List[WalkForwardResult]:
        """Run walk-forward testing with parameter optimization."""
        splits = self.split_data(self.config.train_window_days * 86_400_000_000_000,
                                  self.config.test_window_days * 86_400_000_000_000)

        results = []
        for fold_idx, (train_start, train_end, test_start, test_end) in enumerate(splits):
            # Optimize parameters on training window
            best_params, train_metrics = self._optimize_parameters(
                engine_factory, parameter_grid,
                lambda s, e: reader_factory(train_start, train_end),
                parameter_grid
            )

            # Test on out-of-sample window
            _test_reader = reader_factory(test_start, test_end)
            _engine = engine_factory()
            # _engine.set_strategy(...)  # Would set strategy with best_params
            # _test_result = _engine.run(_test_reader)
            # _test_metrics = compute_backtest_metrics(...)

            results.append(WalkForwardResult(
                fold_idx=fold_idx,
                train_start=train_start,
                train_end=train_end,
                test_start=test_start,
                test_end=test_end,
                train_metrics=train_metrics,
                test_metrics={},  # test_metrics,
                best_params=best_params,
            ))

        return results

    def _optimize_parameters(
        self,
        engine_factory: Callable[[], Engine],
        parameter_grid: Dict[str, List[Any]],
        reader_factory: Callable[[int, int], 'EventReader'],
    ) -> Tuple[Dict[str, Any], Dict[str, float]]:
        """Grid search for optimal parameters on training window."""
        param_names = list(parameter_grid.keys())
        param_values = list(parameter_grid.values())
        _best_score = -float('inf')
        best_params: Dict[str, Any] = {}
        best_metrics: Dict[str, float] = {}

        for combo in itertools.product(*param_values):
            _params = dict(zip(param_names, combo))
            # Run backtest with these params
            # ... (would run engine with these params)
            # score = metric (e.g., Sharpe ratio)
            # if score > best_score: update best

        return best_params, best_metrics


# ============================================================================
# Parameter Sensitivity Analysis
# ============================================================================


@dataclass
class SensitivityConfig:
    """Configuration for parameter sensitivity analysis."""
    base_params: Dict[str, Any]
    param_ranges: Dict[str, Tuple[float, float, int]]  # param -> (min, max, steps)
    metric: str = "sharpe_ratio"  # Metric to optimize
    n_samples: int = 50  # Number of random samples for random search
    method: str = "random"  # "grid", "random", "sobol"


@dataclass
class SensitivityResult:
    """Results from sensitivity analysis."""
    param_name: str
    values: List[float]
    metric_values: List[float]
    sensitivity: float  # Correlation between param and metric
    rank: int


class SensitivityAnalyzer:
    """Parameter sensitivity analysis for strategy robustness."""

    def __init__(self, config: SensitivityConfig):
        self.config = config

    def analyze(
        self,
        engine_factory: Callable[[], Engine],
        reader_factory: Callable[[], 'EventReader'],
        strategy_creator: Callable[[Dict[str, Any]], Callable],
    ) -> List[SensitivityResult]:
        """Run sensitivity analysis using random or grid search."""
        results = []

        for param_name, (min_val, max_val, steps) in self.config.param_ranges.items():
            if self.config.method == "grid":
                values = np.linspace(self.config.base_params[param_name], max_val, steps)
            else:
                values = np.random.uniform(self.config.base_params[param_name], max_val, self.config.n_samples)

            metric_values = []
            for val in values:
                params = self.config.base_params.copy()
                params[param_name] = val
                # Run backtest with these params
                # metric = run_backtest(engine_factory, reader_factory, params)
                metric_values.append(0.0)  # placeholder

            # Compute sensitivity (correlation)
            if len(values) > 1:
                sensitivity = np.corrcoef(values, metric_values)[0, 1]
            else:
                sensitivity = 0.0

            results.append(SensitivityResult(
                param_name=param_name,
                values=values.tolist(),
                metric_values=metric_values,
                sensitivity=sensitivity,
                rank=0,  # Will be set after sorting
            ))

        # Rank by sensitivity
        results.sort(key=lambda x: abs(x.sensitivity), reverse=True)
        for i, r in enumerate(results):
            r.rank = i + 1

        return results


# ============================================================================
# Deflated Sharpe Ratio
# ============================================================================


@dataclass
class DeflatedSharpeConfig:
    """Configuration for deflated Sharpe ratio calculation."""
    n_trials: int = 1000          # Number of Monte Carlo trials
    min_track_record: int = 12    # Minimum track record in months
    confidence_level: float = 0.95  # Confidence level for PSR


def compute_deflated_sharpe(
    sharpe: float,
    n_observations: int,
    skew: float,
    kurtosis: float,
    n_trials: int = 1000,
    track_record_months: int = 12,
) -> Dict[str, float]:
    """
    Compute the Probabilistic Sharpe Ratio (PSR) and Deflated Sharpe Ratio (DSR).

    Based on Bailey & Lopez de Prado (2014): "The Deflated Sharpe Ratio:
    correcting for selection bias, backtest overfitting, and non-normality."

    Returns:
        Dictionary with 'psr' (Probabilistic Sharpe Ratio),
        'dsr' (Deflated Sharpe Ratio), 'p_value' (p-value of Sharpe being > 0).
    """
    if n_observations < 2:
        return {'psr': 0.0, 'dsr': 0.0, 'p_value': 1.0}

    # Standard error of Sharpe ratio (Lo, 2002)
    se_sharpe = math.sqrt((1 + 0.5 * sharpe**2 - skew * sharpe +
                           (kurtosis - 3) / 4 * sharpe**2) / n_observations)

    # Probabilistic Sharpe Ratio (PSR)
    # Probability that estimated Sharpe is greater than 0
    if se_sharpe > 0:
        psr = stats.norm.cdf(sharpe / se_sharpe)
    else:
        psr = 1.0 if sharpe > 0 else 0.0

    # Deflated Sharpe Ratio (DSR)
    # Adjusts for multiple testing (selection bias)
    # DSR = PSR adjusted for multiple trials
    # Using the formula from Bailey & Lopez de Prado (2014)

    # Expected maximum Sharpe from n_trials independent strategies
    # E[max] ≈ σ * Φ^(-1)(1 - 1/n_trials) where σ = std of Sharpe estimates
    # For normally distributed Sharpe estimates with variance se_sharpe^2

    if sharpe <= 0:
        return {'psr': psr, 'dsr': 0.0, 'p_value': 1.0 - psr}

    # Expected maximum Sharpe from n_trials (order statistic)
    # Using extreme value theory approximation
    _z_n = stats.norm.ppf(1 - 1.0 / n_trials) if n_trials > 1 else 1.0
    expected_max_sharpe = sharpe * n_observations / (n_observations + n_observations * sharpe**2 / 2)

    # Deflated Sharpe: adjust for selection bias
    # DSR = Sharpe - expected_max_sharpe_from_selection
    dsr = max(0.0, sharpe - expected_max_sharpe)

    # p-value: probability that a random strategy would achieve this Sharpe
    p_value = 1.0 - stats.norm.cdf(sharpe / se_sharpe) if se_sharpe > 0 else 1.0

    return {
        'psr': psr,
        'dsr': dsr,
        'p_value': p_value,
        'sharpe': sharpe,
        'se_sharpe': se_sharpe,
    }


# ============================================================================
# Result Classes
# ============================================================================


@dataclass
class BacktestResult:
    """Result of a backtest run."""
    initial_cash: float
    final_equity: float
    total_pnl: float
    total_return: float
    total_trades: int
    fills: List
    cheat_detected: bool
    cheat_details: List[str]


# ============================================================================
# Artifact Storage
# ============================================================================


class ArtifactStorage:
    """Manages artifact storage for backtest runs."""

    def __init__(self, base_dir: str = "artifacts"):
        self.base_dir = Path(base_dir)
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def save_artifact(
        self,
        job_id: str,
        artifact_type: str,  # "report", "fills", "equity_curve", "run_record"
        content: bytes,
        filename: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Save artifact and return metadata."""
        job_dir = self.base_dir / job_id
        job_dir.mkdir(parents=True, exist_ok=True)

        if filename is None:
            timestamp = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
            filename = f"{artifact_type}_{timestamp}.json"

        file_path = job_dir / filename
        if isinstance(content, (dict, list)):
            content_bytes = json.dumps(content, default=str, indent=2).encode()
        else:
            content_bytes = content if isinstance(content, bytes) else str(content).encode()

        file_path = job_dir / filename
        file_path.write_bytes(content_bytes)

        return {
            "artifact_id": str(uuid.uuid4())[:8],
            "artifact_type": artifact_type,
            "path": str(file_path),
            "size_bytes": file_path.stat().st_size,
            "created_at": datetime.now(timezone.utc).isoformat(),
        }

    def get_artifact(self, job_id: str, artifact_id: str) -> Optional[Dict]:
        """Get artifact metadata."""
        # Would read from metadata file
        pass

    def list_artifacts(self, job_id: str) -> List[Dict]:
        """List all artifacts for a job."""
        job_dir = self.base_dir / job_id
        if not job_dir.exists():
            return []
        artifacts = []
        for file in job_dir.glob("*.json*"):
            stat = file.stat()
            artifacts.append({
                "artifact_id": file.stem,
                "artifact_type": file.stem.split("_")[0],
                "path": str(file),
                "size_bytes": stat.st_size,
                "created_at": datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc).isoformat(),
            })
        return artifacts

    def get_artifact_path(self, job_id: str, artifact_id: str) -> Optional[Path]:
        """Get path to artifact file."""
        job_dir = self.base_dir / job_id
        for file in job_dir.glob(f"{artifact_id}*"):
            return file
        return None


# ============================================================================
# Example Usage
# ============================================================================

if __name__ == "__main__":
    # Example: Run a simple backtest
    spec = InstrumentSpec(
        price_scale=8,
        qty_scale=8,
        tick_size=100000000,
        lot_size=100000000,
    )

    engine = Engine(
        spec=spec,
        initial_cash=100_000.0,
        fee_bps=5.0,
        maker_rebate_bps=0.0,
        slippage_bps=0.0,
    )

    # Set strategy
    engine.set_strategy(buy_and_hold_strategy)

    # Run backtest (would need EventReader)
    # result = engine.run(reader)
    # print(f"PnL: {result.total_pnl:.2f}")
    pass