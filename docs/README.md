# Documentation index

| # | Document | Purpose |
|---|---|---|
| 01 | [PRD](01-PRD.md) | Product requirements: goals, users, features, success metrics |
| 02 | [TRD](02-TRD.md) | Technical requirements and design for every service |
| 03 | [Architecture](03-ARCHITECTURE.md) | Diagrams, data flows, failure modes, deployment views |
| 04 | [API specification](04-API-SPEC.md) | REST, WebSocket and gRPC contracts |
| 05 | [Data model](05-DATA-MODEL.md) | Postgres, ClickHouse, Redis, event-log formats |
| 06 | [Order book spec](06-ORDER-BOOK-SPEC.md) | Sync algorithm, invariants, optimized structure, metrics formulas |
| 07 | [Paper trading spec](07-PAPER-TRADING-SPEC.md) | Order lifecycle, fill model, fees, ledger |
| 08 | [Backtesting methodology](08-BACKTESTING-METHODOLOGY.md) | Execution model, anti-lookahead, reporting |
| 09 | [Security](09-SECURITY.md) | Threat model, controls, compliance notes |
| 10 | [Testing strategy](10-TESTING-STRATEGY.md) | Test levels, tools, CI gates |
| 11 | [Performance and observability](11-PERFORMANCE-AND-OBSERVABILITY.md) | Latency definitions, budgets, metrics, dashboards |
| 12 | [DevOps and deployment](12-DEVOPS-DEPLOYMENT.md) | Compose profiles, CI/CD, free hosting |
| 13 | [Development guide](13-DEVELOPMENT-GUIDE.md) | Setup, conventions, workflow, definition of done |
| 14 | [Roadmap](14-ROADMAP.md) | Phases, exit criteria, backlog |
| 15 | [Risk register](15-RISK-REGISTER.md) | Risks and mitigations |
| 16 | [Glossary](16-GLOSSARY.md) | Terms and formulas |
| - | [ADRs](adr/README.md) | Architecture decision records |

## How to use these docs

- **PRD** says *what and why*; **TRD** says *how*. Specs (04 to 08) are the detailed contracts. If two docs disagree, the specs and ADRs win, then the TRD, then the PRD. Fix the disagreement in the same change.
- Anything marked **VERIFY** depends on an external service (exchange limits, free-tier terms). Check the official docs when you reach that phase and update the doc.
- Numbers marked **target** are goals, not measurements. Replace them with measured results in `benchmarks/` as they become available.

## Document status

All documents are v0.1 (planning). Owner: project author. Review before starting each roadmap phase.
