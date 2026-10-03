# ADR-0009: Paper trading only

**Status:** Accepted
**Date:** 2026-10-02

## Context
Handling real money or giving investment advice introduces regulatory exposure (for example SEBI in India), custody and security obligations, and liability, none of which add to the engineering goals.

## Decision
The platform simulates trading against live order books. No real orders, funds, or brokerage credentials. All UI and docs label results as simulated and not investment advice.

## Consequences
+ Simplifies compliance and security scope. - Cannot demonstrate real execution; mitigated by realistic book-walking fills and honest backtests.

## Alternatives considered
Broker integration for live trading - rejected for v1.
