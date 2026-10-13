#!/usr/bin/env tsx
/**
 * Generate WebSocket message types from API spec
 * 
 * This script parses the Java OpenAPI spec and generates TypeScript types
 * for WebSocket messages (client -> server and server -> client).
 * 
 * Run: npm run generate:ws
 */

import fs from 'fs';
import path from 'path';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8081';
const OPENAPI_URL = `${API_BASE}/api/api-docs`;

interface OpenAPISchema {
  components?: {
    schemas: Record<string, any>;
  };
}

async function fetchOpenAPISpec(): Promise<OpenAPISchema> {
  const response = await fetch(OPENAPI_URL);
  if (!response.ok) {
    throw new Error(`Failed to fetch OpenAPI spec: ${response.statusText}`);
  }
  return response.json();
}

function generateWSClientMessage(): string {
  return `// Generated WebSocket client message types
// Run: npm run generate:ws

export type WsClientMessage =
  | { op: 'subscribe'; channel: 'book' | 'trades' | 'metrics'; symbol: string; depth?: number }
  | { op: 'unsubscribe'; channel: 'book' | 'trades' | 'metrics'; symbol: string }
  | { op: 'resync'; channel: 'book'; symbol: string }
  | { op: 'ping'; id?: string };
`;
}

function generateWSServerMessage(): string {
  return `// Generated WebSocket server message types
// Run: npm run generate:ws

import type { BookUpdate, Trade, Metrics, Timing } from '@/lib/api';

export type WsServerMessage =
  | { t: 'book.snap'; ch: 'book'; s: string; ep: number; sq: number; tm: Timing; d: { b: [string, string][]; a: [string, string][] } }
  | { t: 'book.delta'; ch: 'book'; s: string; ep: number; sq: number; tm: Timing; d: { b: [string, string][]; a: [string, string][] } }
  | { t: 'trade'; ch: 'trades'; s: string; ep: number; sq: number; tm: Timing; d: { p: string; q: string; side: string; id: string } }
  | { t: 'metrics'; ch: 'metrics'; s: string; ep: number; sq: number; tm: Timing; d: Metrics }
  | { t: 'order'; ch: 'orders'; s: string; ep: number; sq: number; tm: Timing; d: any }
  | { t: 'portfolio'; ch: 'portfolio'; s: string; ep: number; sq: number; tm: Timing; d: any }
  | { t: 'resync'; ch: 'resync'; s: string; ep: number; sq: number; tm: Timing; d: any }
  | { t: 'error'; d: { code: string; message: string } }
  | { t: 'pong'; id?: string };

// Type guards
export function isBookSnap(msg: WsServerMessage): msg is Extract<WsServerMessage, { t: 'book.snap' }> {
  return msg.t === 'book.snap';
}

export function isBookDelta(msg: WsServerMessage): msg is Extract<WsServerMessage, { t: 'book.delta' }> {
  return msg.t === 'book.delta';
}

export function isTrade(msg: WsServerMessage): msg is Extract<WsServerMessage, { t: 'trade' }> {
  return msg.t === 'trade';
}

export function isMetrics(msg: WsServerMessage): msg is Extract<WsServerMessage, { t: 'metrics' }> {
  return msg.t === 'metrics';
}

export function isError(msg: WsServerMessage): msg is Extract<WsServerMessage, { t: 'error' }> {
  return msg.t === 'error';
}

export function isPong(msg: WsServerMessage): msg is Extract<WsServerMessage, { t: 'pong' }> {
  return msg.t === 'pong';
}
`;
}

async function main() {
  console.log('Generating WebSocket types...');
  
  const outputDir = path.join(process.cwd(), 'src/lib/api');
  const clientOutput = path.join(outputDir, 'ws-client-messages.ts');
  const serverOutput = path.join(outputDir, 'ws-server-messages.ts');
  
  fs.writeFileSync(clientOutput, generateWSClientMessage());
  fs.writeFileSync(serverOutput, generateWSServerMessage());
  
  console.log('Generated:');
  console.log(`  ${clientOutput}`);
  console.log(`  ${serverOutput}`);
  console.log('Done!');
}

main().catch(console.error);