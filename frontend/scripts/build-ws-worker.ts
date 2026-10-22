#!/usr/bin/env tsx
/**
 * Build WebSocket worker from TypeScript to JavaScript
 * 
 * Compiles public/ws-worker.ts to public/ws-worker.js
 * Run: npm run build:ws-worker
 */

import { build } from 'esbuild';
import path from 'path';

async function main() {
  console.log('Building WebSocket worker...');
  
  const entryPoint = path.join(process.cwd(), 'public', 'ws-worker.ts');
  const outfile = path.join(process.cwd(), 'public', 'ws-worker.js');
  
  try {
    await build({
      entryPoints: [entryPoint],
      outfile,
      platform: 'node',
      format: 'esm',
      target: 'es2020',
      bundle: true,
      minify: true,
      sourcemap: false,
      external: [], // Bundle everything
    });
    
    console.log(`Built: ${outfile}`);
    console.log('Done!');
  } catch (error) {
    console.error('Build failed:', error);
    process.exit(1);
  }
}

main();