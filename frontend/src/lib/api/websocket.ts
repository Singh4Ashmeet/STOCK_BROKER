'use client';

import type { WsClientMessage, WsServerMessage, BookUpdate, Trade, Metrics } from '@/lib/api';
import { authClient } from '@/lib/auth';

type MessageHandler = (message: WsServerMessage) => void;

interface Subscription {
  channel: 'book' | 'trades' | 'metrics';
  symbol: string;
  depth?: number;
}

interface WebSocketState {
  status: 'disconnected' | 'connecting' | 'connected' | 'error';
  lastError: Error | null;
  subscriptions: Subscription[];
}

export class WebSocketClient {
  private ws: WebSocket | null = null;
  private ticket: string | null = null;
  private handlers: Set<MessageHandler> = new Set();
  private state: WebSocketState = {
    status: 'disconnected',
    lastError: null,
    subscriptions: [],
  };
  private stateListeners: Set<(state: WebSocketState) => void> = new Set();
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private reconnectDelay = 1000;
  private pingInterval: NodeJS.Timeout | null = null;

  getState(): WebSocketState {
    return { ...this.state };
  }

  subscribeToState(listener: (state: WebSocketState) => void): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  private notifyStateChange(): void {
    this.stateListeners.forEach(listener => listener(this.getState()));
  }

  addMessageHandler(handler: MessageHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  private async getTicket(): Promise<string> {
    if (this.ticket) return this.ticket;

    const accessToken = authClient.getAccessToken();
    if (!accessToken) {
      throw new Error('No access token available for WebSocket ticket');
    }

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

    const data = await response.json();
    this.ticket = data.ticket;
    return this.ticket!;
  }

  async connect(): Promise<void> {
    if (this.ws?.readyState === WebSocket.OPEN) return;

    this.updateState({ status: 'connecting' });

    try {
      const ticket = await this.getTicket();
      // Include context path /api for Java backend
      const WS_BASE = process.env.NEXT_PUBLIC_WS_URL || 'ws://localhost:8081';
      const wsUrl = `${WS_BASE}/api/ws/v1?ticket=${ticket}`;

      this.ws = new WebSocket(wsUrl);

      this.ws.onopen = () => {
        console.log('[WS] Connected');
        this.reconnectAttempts = 0;
        this.updateState({ status: 'connected', lastError: null });
        this.startPing();
        this.resubscribe();
      };

      this.ws.onmessage = (event) => {
        try {
          const message: WsServerMessage = JSON.parse(event.data);
          this.handleMessage(message);
        } catch (error) {
          console.error('[WS] Parse error:', error);
        }
      };

      this.ws.onclose = (event) => {
        console.log('[WS] Closed:', event.code, event.reason);
        this.stopPing();
        this.updateState({ status: 'disconnected' });
        this.scheduleReconnect();
      };

      this.ws.onerror = (error) => {
        console.error('[WS] Error:', error);
        this.updateState({ status: 'error', lastError: new Error('WebSocket error') });
      };
    } catch (error) {
      this.updateState({ status: 'error', lastError: error as Error });
      this.scheduleReconnect();
    }
  }

  private handleMessage(message: WsServerMessage): void {
    this.handlers.forEach(handler => {
      try {
        handler(message);
      } catch (error) {
        console.error('[WS] Handler error:', error);
      }
    });
  }

  private startPing(): void {
    this.pingInterval = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.send({ op: 'ping', id: Date.now().toString() });
      }
    }, 10000);
  }

  private stopPing(): void {
    if (this.pingInterval) {
      clearInterval(this.pingInterval);
      this.pingInterval = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.log('[WS] Max reconnect attempts reached');
      return;
    }

    const delay = this.reconnectDelay * Math.pow(2, this.reconnectAttempts);
    this.reconnectAttempts++;

    console.log(`[WS] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
    setTimeout(() => this.connect(), delay);
  }

  private resubscribe(): void {
    this.state.subscriptions.forEach(sub => {
      this.send({
        op: 'subscribe',
        channel: sub.channel,
        symbol: sub.symbol,
        depth: sub.depth,
      });
    });
  }

  send(message: WsClientMessage): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(message));
    } else {
      console.warn('[WS] Cannot send, not connected');
    }
  }

  subscribe(channel: 'book' | 'trades' | 'metrics', symbol: string, depth?: number): void {
    const sub: Subscription = { channel, symbol, depth };
    if (!this.state.subscriptions.some(s => s.channel === channel && s.symbol === symbol)) {
      this.updateState({ subscriptions: [...this.state.subscriptions, sub] });
    }
    this.send({ op: 'subscribe', channel, symbol, depth });
  }

  unsubscribe(channel: 'book' | 'trades' | 'metrics', symbol: string): void {
    this.updateState({
      subscriptions: this.state.subscriptions.filter(
        s => !(s.channel === channel && s.symbol === symbol)
      ),
    });
    this.send({ op: 'unsubscribe', channel, symbol });
  }

  resync(channel: 'book', symbol: string): void {
    this.send({ op: 'resync', channel, symbol });
  }

  disconnect(): void {
    this.stopPing();
    this.maxReconnectAttempts = 0;
    if (this.ws) {
      this.ws.close(1000, 'Client disconnect');
      this.ws = null;
    }
    this.updateState({ status: 'disconnected', subscriptions: [] });
  }

  private updateState(partial: Partial<WebSocketState>): void {
    this.state = { ...this.state, ...partial };
    this.notifyStateChange();
  }
}

// Singleton instance
export const wsClient = new WebSocketClient();

// React hook for using WebSocket in components
export function useWebSocket() {
  const [state, setState] = useState<WebSocketState>(wsClient.getState());

  useEffect(() => {
    const unsubscribe = wsClient.subscribeToState(setState);
    return unsubscribe;
  }, []);

  return {
    ...state,
    connect: () => wsClient.connect(),
    disconnect: () => wsClient.disconnect(),
    subscribe: (channel: 'book' | 'trades' | 'metrics', symbol: string, depth?: number) =>
      wsClient.subscribe(channel, symbol, depth),
    unsubscribe: (channel: 'book' | 'trades' | 'metrics', symbol: string) =>
      wsClient.unsubscribe(channel, symbol),
    resync: (symbol: string) => wsClient.resync('book', symbol),
    onMessage: (handler: MessageHandler) => wsClient.addMessageHandler(handler),
  };
}

// Need to import useState and useEffect
import { useState, useEffect } from 'react';
