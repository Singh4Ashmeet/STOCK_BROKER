# ADR-0007: Multi-user auth with JWT access + rotating refresh tokens

**Status:** Accepted
**Date:** 2026-10-02

## Context
The product is multi-user and public-facing; sessions must be revocable and resilient to token theft, while WebSocket and REST need stateless validation on the hot path.

## Decision
Short-lived (about 15 min) asymmetric-signed JWT access tokens; opaque rotating refresh tokens (hashed in Postgres) with reuse detection that revokes the token family; Argon2id passwords; refresh token in an httpOnly SameSite=Strict cookie handled by Next.js; access token in memory; WebSocket uses a single-use 30 s ticket instead of the access token in the URL.

## Consequences
+ Revocation and theft detection, no tokens in URLs or localStorage. - More moving parts (rotation, CSRF defense on refresh, ticket endpoint).

## Alternatives considered
Server sessions in Redis - simpler revocation but stateful on every request. Long-lived JWTs - rejected (poor revocation).
