'use client';

import { useEffect, useRef, useCallback, useState } from 'react';
import { useMarketStore, selectConnectionStatus, selectIsResyncing } from '@/lib/store';
import { useAuth } from '@/lib/auth';

interface UseWebSocketWorkerReturn {
  connect: () => void;
  disconnect: () => void;
  subscribe: (channel: 'book' | 'trades' | 'metrics', symbol: string, depth?: number) => void;
  unsubscribe: (channel: 'book' | 'trades' | 'metrics', symbol: string) => void;
  resync: (symbol: string) => void;
  ping: () => void;
  connectionStatus: ReturnType<typeof selectConnectionStatus>;
  isResyncing: (symbol: string) => boolean;
}

export function useWebSocketWorker(): UseWebSocketWorkerReturn {
  const workerRef = useRef<Worker | null>(null);
  const { accessToken } = useAuth();
  const connectionStatus = useMarketStore(selectConnectionStatus);
  const isResyncing = useCallback(
    (symbol: string) => useMarketStore.getState().isResyncing(symbol),
    []
  );
  const [workerReady, setWorkerReady] = useState(false);

  // Initialize worker
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const worker = new Worker('/ws-worker.js', { type: 'module' });
    workerRef.current = worker;

    worker.onmessage = (event) => {
      const msg = event.data;

      switch (msg.type) {
        case 'state': {
          const { status, error } = msg;
          if (status === 'connected') {
            useMarketStore.getState().recordConnected();
          } else if (status === 'disconnected') {
            useMarketStore.getState().recordDisconnected();
          } else if (status === 'connecting') {
            useMarketStore.getState().setConnectionStatus('connecting');
          } else if (status === 'error') {
            useMarketStore.getState().setConnectionStatus('error', error);
          } else if (status === 'resyncing') {
            useMarketStore.getState().setConnectionStatus('resyncing');
          }
          break;
        }

        case 'batch': {
          const { messages } = msg;
          const received = performance.now();
          
          messages.forEach((message: any) => {
            // Record message for performance tracking
            let serverSent: number | undefined;
            if (message.tm && message.tm.gateway_send_ts_ns) {
              serverSent = Number(message.tm.gateway_send_ts_ns) / 1_000_000;
            }
            useMarketStore.getState().recordMessage(message, received, serverSent);
            
            // Process message
            if (message.t === 'book.snap') {
              useMarketStore.getState().setBook(message.s, message);
              useMarketStore.getState().setResyncing(message.s, false);
            } else if (message.t === 'book.delta') {
              useMarketStore.getState().applyDelta(message.s, message);
            } else if (message.t === 'trade') {
              useMarketStore.getState().addTrade(message.s, message);
            } else if (message.t === 'metrics') {
              useMarketStore.getState().setMetrics(message.s, message.d);
            } else if (message.t === 'error') {
              console.error('[WS] Server error:', message.d);
            }
          });
          break;
        }

        case 'latency': {
          const { clientSent, serverSent, received } = msg;
          if (serverSent) {
            useMarketStore.getState().recordMessage(null, received, serverSent, clientSent);
          }
          break;
        }
      }
    };

    worker.onerror = (error) => {
      console.error('[WS Worker] Error:', error);
      useMarketStore.getState().setConnectionStatus('error', error.message);
    };

    // Worker is ready immediately for module workers
    setWorkerReady(true);

    return () => {
      worker.terminate();
      workerRef.current = null;
      setWorkerReady(false);
    };
  }, []);

  // Request ticket and connect
  const connect = useCallback(async () => {
    if (!workerReady || !workerRef.current || !accessToken) return;

    try {
      const response = await fetch('/api/ws/ticket', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${accessToken}`,
        },
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error('Failed to get WebSocket ticket');
      }

      const { ticket } = await response.json();
      
      const wsUrl = process.env.NEXT_PUBLIC_WS_URL || 'ws://localhost:8081';
      (workerRef.current as Worker | null)?.postMessage({ type: 'connect', ticket, wsUrl });
    } catch (error) {
      console.error('[WS] Connect error:', error);
      useMarketStore.getState().setConnectionStatus('error', 'Failed to connect');
    }
  }, [accessToken, workerReady]);

  const disconnect = useCallback(() => {
    (workerRef.current as Worker | null)?.postMessage({ type: 'disconnect' });
  }, []);

  const subscribe = useCallback((
    channel: 'book' | 'trades' | 'metrics',
    symbol: string,
    depth?: number
  ) => {
    const key = `${channel}:${symbol}`;
    useMarketStore.getState().addSubscription(key, { channel, symbol, depth });
    (workerRef.current as Worker | null)?.postMessage({ type: 'subscribe', channel, symbol, depth });
  }, []);

  const unsubscribe = useCallback((
    channel: 'book' | 'trades' | 'metrics',
    symbol: string
  ) => {
    const key = `${channel}:${symbol}`;
    useMarketStore.getState().removeSubscription(key);
    (workerRef.current as Worker | null)?.postMessage({ type: 'unsubscribe', channel, symbol });
  }, []);

  const resync = useCallback((symbol: string) => {
    useMarketStore.getState().setResyncing(symbol, true);
    useMarketStore.getState().setConnectionStatus('resyncing');
    (workerRef.current as Worker | null)?.postMessage({ type: 'resync', channel: 'book', symbol });
  }, []);

  const ping = useCallback(() => {
    (workerRef.current as Worker | null)?.postMessage({ type: 'ping' });
  }, []);

  return {
    connect,
    disconnect,
    subscribe,
    unsubscribe,
    resync,
    ping,
    connectionStatus,
    isResyncing,
  };
}
