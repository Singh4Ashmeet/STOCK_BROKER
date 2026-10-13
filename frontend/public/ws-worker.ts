// WebSocket Web Worker
// Handles WebSocket connection, reconnection, message batching, and forwards to main thread

type WsMessage = 
  | { type: 'book.snap'; s: string; ep: number; sq: number; tm: any; d: any }
  | { type: 'book.delta'; s: string; ep: number; sq: number; tm: any; d: any }
  | { type: 'trade'; s: string; ep: number; sq: number; tm: any; d: any }
  | { type: 'metrics'; s: string; ep: number; sq: number; tm: any; d: any }
  | { type: 'error'; d: { code: string; message: string } }
  | { type: 'pong'; id?: string }
  | { type: 'resync'; s: string; ep: number; sq: number; tm: any; d: any };

type WorkerMessage = 
  | { type: 'connect'; ticket: string; wsUrl: string }
  | { type: 'disconnect' }
  | { type: 'subscribe'; channel: 'book' | 'trades' | 'metrics'; symbol: string; depth?: number }
  | { type: 'unsubscribe'; channel: 'book' | 'trades' | 'metrics'; symbol: string }
  | { type: 'resync'; channel: 'book'; symbol: string }
  | { type: 'ping' }
  | { type: 'message'; data: WsMessage }
  | { type: 'state'; status: 'connecting' | 'connected' | 'disconnected' | 'error'; error?: string }
  | { type: 'latency'; clientSent: number; serverSent: number; received: number };

interface Subscription {
  channel: 'book' | 'trades' | 'metrics';
  symbol: string;
  depth?: number;
}

let ws: WebSocket | null = null;
let ticket: string | null = null;
let wsUrl: string | null = null;
const subscriptions = new Map<string, Subscription>();
let reconnectAttempts = 0;
const maxReconnectAttempts = 10;
let reconnectDelay = 1000;
let reconnectTimer: number | null = null;
let pingInterval: number | null = null;
let currentState: 'disconnected' | 'connecting' | 'connected' | 'error' = 'disconnected';
let messageBuffer: WsMessage[] = [];
let flushScheduled = false;

function postMessage(msg: WorkerMessage) {
  self.postMessage(msg);
}

function setState(status: 'connecting' | 'connected' | 'disconnected' | 'error', error?: string) {
  currentState = status;
  postMessage({ type: 'state', status, error });
}

function scheduleFlush() {
  if (!flushScheduled) {
    flushScheduled = true;
    requestAnimationFrame(flushMessages);
  }
}

function flushMessages() {
  flushScheduled = false;
  if (messageBuffer.length > 0) {
    const batch = messageBuffer.splice(0, messageBuffer.length);
    postMessage({ type: 'batch', messages: batch });
  }
}

function handleMessage(event: MessageEvent) {
  try {
    const data = JSON.parse(event.data);
    const received = performance.now();
    
    // Track latency if server timestamp present
    if (data.tm && data.tm.gateway_send_ts_ns) {
      const serverSent = Number(data.tm.gateway_send_ts_ns) / 1_000_000;
      postMessage({ type: 'latency', clientSent: 0, serverSent, received });
    }
    
    messageBuffer.push(data);
    scheduleFlush();
  } catch (e) {
    console.error('[Worker] Parse error:', e);
  }
}

function connect() {
  if (!ticket || !wsUrl) return;
  
  setState('connecting');
  
  ws = new WebSocket(`${wsUrl}/ws/v1?ticket=${ticket}`);
  
  ws.onopen = () => {
    reconnectAttempts = 0;
    reconnectDelay = 1000;
    setState('connected');
    
    // Resubscribe
    subscriptions.forEach(sub => {
      ws?.send(JSON.stringify({
        op: 'subscribe',
        channel: sub.channel,
        symbol: sub.symbol,
        depth: sub.depth,
      }));
    });
    
    // Start ping interval
    pingInterval = self.setInterval(() => {
      if (ws?.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ op: 'ping', id: Date.now().toString() }));
      }
    }, 10000);
  };
  
  ws.onmessage = handleMessage;
  
  ws.onclose = (event) => {
    if (pingInterval) {
      clearInterval(pingInterval);
      pingInterval = null;
    }
    setState('disconnected');
    scheduleReconnect();
  };
  
  ws.onerror = (error) => {
    setState('error', 'WebSocket error');
  };
}

function scheduleReconnect() {
  if (reconnectAttempts >= maxReconnectAttempts) {
    postMessage({ type: 'state', status: 'error', error: 'Max reconnect attempts reached' });
    return;
  }
  
  const delay = reconnectDelay * Math.pow(1.5, reconnectAttempts);
  reconnectAttempts++;
  
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
  }
  
  reconnectTimer = self.setTimeout(() => {
    connect();
  }, delay);
}

function disconnect() {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (pingInterval) {
    clearInterval(pingInterval);
    pingInterval = null;
  }
  if (ws) {
    ws.close(1000, 'Worker disconnect');
    ws = null;
  }
  setState('disconnected');
}

// Main message handler
self.onmessage = (event: MessageEvent<WorkerMessage>) => {
  const msg = event.data;
  
  switch (msg.type) {
    case 'connect':
      ticket = msg.ticket;
      wsUrl = msg.wsUrl;
      connect();
      break;
      
    case 'disconnect':
      disconnect();
      break;
      
    case 'subscribe': {
      const key = `${msg.channel}:${msg.symbol}`;
      subscriptions.set(key, msg);
      if (ws?.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          op: 'subscribe',
          channel: msg.channel,
          symbol: msg.symbol,
          depth: msg.depth,
        }));
      }
      break;
    }
      
    case 'unsubscribe': {
      const key = `${msg.channel}:${msg.symbol}`;
      subscriptions.delete(key);
      if (ws?.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          op: 'unsubscribe',
          channel: msg.channel,
          symbol: msg.symbol,
        }));
      }
      break;
    }
      
    case 'resync': {
      if (ws?.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          op: 'resync',
          channel: msg.channel,
          symbol: msg.symbol,
        }));
      }
      break;
    }
      
    case 'ping': {
      if (ws?.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ op: 'ping', id: Date.now().toString() }));
      }
      break;
    }
  }
};

export {};