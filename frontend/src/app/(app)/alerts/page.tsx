'use client';

import { useEffect, useState, useCallback } from 'react';
import { useAuth } from '@/lib/auth';
import { alertsApi } from '@/lib/api';
import { formatTimestamp } from '@/lib/utils';

interface Alert {
  alert_id: string;
  symbol: string;
  metric: 'PRICE' | 'SPREAD' | 'IMBALANCE';
  op: 'GT' | 'LT';
  threshold: string;
  status: 'ACTIVE' | 'TRIGGERED' | 'DISABLED';
  cooldown_s: number;
  last_triggered_at?: string;
  created_at: string;
}

interface AlertListResponse {
  alerts: Alert[];
  next_cursor?: string;
}

interface CreateAlertRequest {
  symbol: string;
  metric: 'PRICE' | 'SPREAD' | 'IMBALANCE';
  op: 'GT' | 'LT';
  threshold: string;
  cooldown_s: number;
}

interface UpdateAlertRequest {
  status?: 'ACTIVE' | 'TRIGGERED' | 'DISABLED';
  cooldown_s?: number;
}

interface Toast {
  type: 'success' | 'error';
  message: string;
}

interface StatBoxProps {
  label: string;
  value: number;
  color: string;
  icon: string;
}

function StatBox({ label, value, color, icon }: StatBoxProps) {
  const colorMap = {
    green: 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400',
    orange: 'bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-400',
    gray: 'bg-surface-100 text-surface-800 dark:bg-surface-800 dark:text-surface-400',
  };
  
  return (
    <div className={`p-4 rounded-xl ${color}`}>
      <div className="flex items-center justify-between">
        <span className="text-2xl">{icon}</span>
        <span className="text-3xl font-bold">{value}</span>
      </div>
      <p className="text-sm mt-1 opacity-80">{label}</p>
    </div>
  );
}

interface ToastType {
  type: 'success' | 'error';
  message: string;
}

interface CreateAlertRequest {
  symbol: string;
  metric: 'PRICE' | 'SPREAD' | 'IMBALANCE';
  op: 'GT' | 'LT';
  threshold: string;
  cooldown_s: number;
}

interface UpdateAlertRequest {
  status?: 'ACTIVE' | 'TRIGGERED' | 'DISABLED';
  cooldown_s?: number;
}

interface ToastType {
  type: 'success' | 'error';
  message: string;
}

export default function AlertsPage() {
  const { user } = useAuth();
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [formData, setFormData] = useState<CreateAlertRequest>({
    symbol: '',
    metric: 'PRICE',
    op: 'GT',
    threshold: '',
    cooldown_s: 60,
  });
  const [submitting, setSubmitting] = useState(false);
  const [toast, setToast] = useState<ToastType | null>(null);
  const [cursor, setCursor] = useState<string | undefined>();

  const loadAlerts = useCallback(async (cursor?: string) => {
    try {
      const response = await alertsApi.listAlerts(50, cursor);
      if (cursor) {
        setAlerts(prev => [...prev, ...response.alerts]);
      } else {
        setAlerts(response.alerts);
      }
      setCursor(response.next_cursor);
    } catch (error) {
      console.error('Failed to load alerts:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAlerts();
  }, [loadAlerts]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    try {
      await alertsApi.createAlert(formData);
      setShowCreate(false);
      setFormData({ symbol: '', metric: 'PRICE', op: 'GT', threshold: '', cooldown_s: 60 });
      loadAlerts();
      showToast('success', 'Alert created successfully');
    } catch (error: any) {
      showToast('error', error.message || 'Failed to create alert');
    } finally {
      setSubmitting(false);
    }
  };

  const handleUpdate = async (alertId: string, update: UpdateAlertRequest) => {
    try {
      await alertsApi.updateAlert(alertId, update);
      loadAlerts();
      showToast('success', 'Alert updated');
    } catch (error: any) {
      showToast('error', error.message || 'Failed to update alert');
    }
  };

  const handleDelete = async (alertId: string) => {
    if (!confirm('Are you sure you want to delete this alert?')) return;
    try {
      await alertsApi.deleteAlert(alertId);
      loadAlerts();
      showToast('success', 'Alert deleted');
    } catch (error: any) {
      showToast('error', error.message || 'Failed to delete alert');
    }
  };

  const showToast = (type: 'success' | 'error', message: string) => {
    setToast({ type, message });
    setTimeout(() => setToast(null), 5000);
  };

  const activeAlerts = alerts.filter(a => a.status === 'ACTIVE');
  const triggeredAlerts = alerts.filter(a => a.status === 'TRIGGERED');
  const disabledAlerts = alerts.filter(a => a.status === 'DISABLED');

  const alertRows = alerts.map((alert, i) => (
    <tr key={i} className="hover:bg-surface-50 dark:hover:bg-surface-800/50">
      <td className="py-3 font-medium text-surface-900 dark:text-surface-50">{alert.symbol}</td>
      <td className="py-3 text-surface-600 dark:text-surface-400">{alert.metric}</td>
      <td className="py-3">
        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${
          alert.op === 'GT' 
            ? 'bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400' 
            : 'bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-400'
        }`}>
          {alert.op === 'GT' ? '>' : '<'}
        </span>
      </td>
      <td className="py-3 font-mono text-surface-900 dark:text-surface-50">{alert.threshold}</td>
      <td className="py-3 text-surface-600 dark:text-surface-400">{alert.cooldown_s}s</td>
      <td className="py-3">
        <select
          value={alert.status}
          onChange={(e) => handleUpdate(alert.alert_id, { status: e.target.value as 'ACTIVE' | 'TRIGGERED' | 'DISABLED' })}
          className={`px-2 py-1 rounded-full text-xs font-medium ${
            alert.status === 'ACTIVE' ? 'bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400' :
            alert.status === 'TRIGGERED' ? 'bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-400' :
            'bg-surface-100 text-surface-800 dark:bg-surface-800 dark:text-surface-400'
          }`}
        >
          <option value="ACTIVE">Active</option>
          <option value="TRIGGERED">Triggered</option>
          <option value="DISABLED">Disabled</option>
        </select>
      </td>
      <td className="py-3 text-surface-600 dark:text-surface-400">{alert.last_triggered_at ? formatTimestamp(alert.last_triggered_at) : '—'}</td>
      <td className="py-3 text-surface-600 dark:text-surface-400">{formatTimestamp(alert.created_at)}</td>
      <td className="py-3">
        <button
          onClick={() => handleDelete(alert.alert_id)}
          className="text-sm text-red-600 hover:text-red-500 underline"
        >
          Delete
        </button>
      </td>
    </tr>
  ));

  return (
    <div className="min-h-screen bg-surface-50 dark:bg-surface-950 p-4 sm:p-6 lg:p-8">
      <div className="mb-8 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-surface-900 dark:text-surface-50">Alerts</h1>
          <p className="mt-1 text-surface-600 dark:text-surface-400">Price, spread, and imbalance notifications</p>
        </div>
        <button
          onClick={() => setShowCreate(true)}
          className="rounded-lg bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 transition-colors"
        >
          Create Alert
        </button>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
        <StatBox label="Active" value={activeAlerts.length} color="bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-400" icon="🔔" />
        <StatBox label="Triggered" value={triggeredAlerts.length} color="bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-400" icon="⚡" />
        <StatBox label="Disabled" value={disabledAlerts.length} color="bg-surface-100 text-surface-800 dark:bg-surface-800 dark:text-surface-400" icon="🔕" />
      </div>

      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800 p-6 w-full max-w-md">
            <h2 className="text-lg font-semibold text-surface-900 dark:text-surface-50 mb-4">Create Alert</h2>
            <form onSubmit={handleCreate} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Symbol</label>
                <input
                  type="text"
                  value={formData.symbol}
                  onChange={(e) => setFormData({ ...formData, symbol: e.target.value })}
                  className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                  placeholder="BINANCE:BTC-USDT"
                  required
                />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Metric</label>
                  <select
                    value={formData.metric}
                    onChange={(e) => setFormData({ ...formData, metric: e.target.value as 'PRICE' | 'SPREAD' | 'IMBALANCE' })}
                    className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                  >
                    <option value="PRICE">Price</option>
                    <option value="SPREAD">Spread (bps)</option>
                    <option value="IMBALANCE">Imbalance</option>
                  </select>
                </div>
                <div>
                  <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Condition</label>
                  <select
                    value={formData.op}
                    onChange={(e) => setFormData({ ...formData, op: e.target.value as 'GT' | 'LT' })}
                    className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                  >
                    <option value="GT">Greater Than</option>
                    <option value="LT">Less Than</option>
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Threshold</label>
                  <input
                    type="text"
                    value={formData.threshold}
                    onChange={(e) => setFormData({ ...formData, threshold: e.target.value })}
                    className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                    placeholder="50000"
                    required
                  />
                </div>
                <div>
                  <label className="block text-sm font-medium text-surface-700 dark:text-surface-300 mb-1">Cooldown (s)</label>
                  <input
                    type="number"
                    value={formData.cooldown_s}
                    onChange={(e) => setFormData({ ...formData, cooldown_s: parseInt(e.target.value) })}
                    className="w-full rounded-lg border border-surface-300 dark:border-surface-700 bg-white dark:bg-surface-800 px-3 py-2 text-surface-900 dark:text-surface-50"
                    defaultValue={60}
                    min={1}
                  />
                </div>
              </div>
              <div className="flex gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setShowCreate(false)}
                  className="flex-1 rounded-lg border border-surface-300 dark:border-surface-700 px-4 py-2 text-sm font-medium text-surface-700 dark:text-surface-300 hover:bg-surface-100 dark:hover:bg-surface-800"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="flex-1 rounded-lg bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
                >
                  {submitting ? 'Creating...' : 'Create'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {toast && (
        <div className="fixed bottom-4 right-4 z-50">
          <div className={`px-4 py-3 rounded-lg shadow-lg flex items-center gap-2 ${
            toast.type === 'success' ? 'bg-green-600 text-white' : 'bg-red-600 text-white'
          }`}>
            {toast.message}
          </div>
        </div>
      )}

      <div className="bg-white dark:bg-surface-900 rounded-xl border border-surface-200 dark:border-surface-800">
        <div className="p-4 border-b border-surface-200 dark:border-surface-800 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-surface-900 dark:text-surface-50">Your Alerts</h2>
          <span className="text-xs text-surface-500">{alerts.length} total</span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-surface-200 dark:border-surface-800 text-left text-sm text-surface-500">
                <th className="pb-3 font-medium">Symbol</th>
                <th className="pb-3 font-medium">Metric</th>
                <th className="pb-3 font-medium">Condition</th>
                <th className="pb-3 font-medium">Threshold</th>
                <th className="pb-3 font-medium">Cooldown</th>
                <th className="pb-3 font-medium">Status</th>
                <th className="pb-3 font-medium">Last Triggered</th>
                <th className="pb-3 font-medium">Created</th>
                <th className="pb-3 font-medium">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-surface-200 dark:divide-surface-800">
              {loading ? (
                <tr>
                  <td className="py-8 text-center text-surface-500" colSpan={9}>
                    Loading...
                  </td>
                </tr>
              ) : alerts.length === 0 ? (
                <tr>
                  <td className="py-8 text-center text-surface-500" colSpan={9}>
                    No alerts yet. Click &quot;Create Alert&quot; to get started.
                  </td>
                </tr>
              ) : (
                alertRows
              )}
            </tbody>
          </table>
        </div>
        {cursor && !loading && (
          <div className="p-4 border-t border-surface-200 dark:border-surface-800 text-center">
            <button
              onClick={() => loadAlerts(cursor)}
              disabled={loading}
              className="px-4 py-2 text-sm bg-primary-600 text-white rounded hover:bg-primary-700 disabled:opacity-50"
            >
              Load More
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

interface ToastType {
  type: 'success' | 'error';
  message: string;
}
