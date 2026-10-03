# ADR-0008: Next.js with client-only live app; WebSocket straight to Java

**Status:** Accepted
**Date:** 2026-10-02

## Context
A single site should host the marketing/benchmark pages (SEO, static) and the live app. Server-rendering a live order book has no benefit, and proxying a high-rate WebSocket through a Node server adds latency. Serverless hosts cannot hold long-lived sockets.

## Decision
Use Next.js App Router with route groups. Marketing is static/ISR; the live app is client-only with a Web Worker for WebSocket handling and canvas rendering. The browser connects directly to the Java WebSocket using a ticket obtained via REST.

## Consequences
+ Lowest latency path, clean separation, good portfolio site. - CORS/Origin handling and an extra ticket step; auth cookie handling split between Next.js and Java.

## Alternatives considered
Vite SPA - simpler for the app but weaker for the marketing site. WebSocket proxied via Next.js - rejected for latency.
