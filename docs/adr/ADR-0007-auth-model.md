# ADR-0007: Multi-user auth with JWT access + rotating refresh tokens

**Status:** Accepted
**Date:** 2026-10-02

## Context
The product is multi-user and public-facing; sessions must be revocable and resilient to token theft, while WebSocket and REST need stateless validation on the hot path.

## Decision
Short-lived (about 15 min) asymmetric-signed JWT access tokens; opaque rotating refresh tokens (hashed in Postgres) with reuse detection that revokes the token family; Argon2id passwords; refresh token in an httpOnly SameSite=Strict cookie handled by Next.js; access token in memory; WebSocket uses a single-use 30 s ticket instead of the access token in the URL. Each access token also carries a non-secret `sid` equal to its refresh-token family UUID. Explicit logout and refresh-token reuse write a bounded Redis `auth:revoked-session:<sid>` marker. JWT validation checks that marker after signature and standard claim validation, so all access tokens for the logged-out session stop authorizing immediately across API instances.

## Consequences
+ Revocation and theft detection, no tokens in URLs or localStorage. A short-lived Redis expiry index records the latest access-token expiry per `sid`, so the revocation marker contains no bearer token and expires no later than that exact access-token expiry; repeated logout does not extend it. Redis failure during a revocation check fails authentication closed, and Redis failure while recording logout makes logout fail rather than claim a successful revocation. Tokens issued before this `sid` claim was deployed are rejected. - More moving parts (rotation, CSRF defense on refresh, ticket endpoint, shared revocation lookup).

## Alternatives considered
Server sessions in Redis - simpler revocation but stateful on every request. Long-lived JWTs - rejected (poor revocation).
