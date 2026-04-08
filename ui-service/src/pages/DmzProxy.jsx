import React, { useEffect, useState, useCallback } from 'react';
import Modal from '../components/Modal';
import * as dmzApi from '../api/dmz';

const DmzProxy = () => {
  const [mappings, setMappings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [healthData, setHealthData] = useState(null);
  const [healthError, setHealthError] = useState('');

  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState('');

  const [removingName, setRemovingName] = useState(null);

  const [form, setForm] = useState({
    name: '',
    listenPort: '',
    targetHost: '',
    targetPort: '',
    qosEnabled: false,
    qosMaxBytesPerSecond: '',
    qosPerConnectionMaxBytesPerSecond: '',
    qosPriority: 5,
    qosBurstAllowancePercent: 20,
  });

  const [controlKey, setControlKey] = useState(() => localStorage.getItem('controlKey') || '');

  const fetchAll = useCallback(async () => {
    setLoading(true);
    setError('');
    setHealthError('');

    const [mappingsResult, healthResult] = await Promise.allSettled([
      dmzApi.listMappings(),
      dmzApi.health(),
    ]);

    if (mappingsResult.status === 'fulfilled') {
      const data = mappingsResult.value;
      setMappings(Array.isArray(data) ? data : data?.mappings || []);
    } else {
      setError(
        mappingsResult.reason?.response?.data?.message ||
        mappingsResult.reason?.message ||
        'Failed to load DMZ mappings.'
      );
      setMappings([]);
    }

    if (healthResult.status === 'fulfilled') {
      setHealthData(healthResult.value);
    } else {
      setHealthError(
        healthResult.reason?.response?.data?.message ||
        healthResult.reason?.message ||
        'Health check failed.'
      );
      setHealthData(null);
    }

    setLoading(false);
  }, []);

  useEffect(() => {
    fetchAll();
  }, [fetchAll]);

  const handleChange = (e) => {
    setForm((prev) => ({ ...prev, [e.target.name]: e.target.value }));
  };

  const handleSaveKey = () => {
    localStorage.setItem('controlKey', controlKey);
    fetchAll();
  };

  const handleAdd = async (e) => {
    e.preventDefault();
    setFormError('');
    const { name, listenPort, targetHost, targetPort } = form;
    if (!name || !listenPort || !targetHost || !targetPort) {
      setFormError('All fields are required.');
      return;
    }
    setSubmitting(true);
    try {
      const payload = {
        name,
        listenPort: Number(listenPort),
        targetHost,
        targetPort: Number(targetPort),
      };
      if (form.qosEnabled) {
        payload.qosPolicy = {
          enabled: true,
          maxBytesPerSecond: form.qosMaxBytesPerSecond ? Number(form.qosMaxBytesPerSecond) * 1048576 : 0,
          perConnectionMaxBytesPerSecond: form.qosPerConnectionMaxBytesPerSecond ? Number(form.qosPerConnectionMaxBytesPerSecond) * 1048576 : 0,
          priority: Number(form.qosPriority) || 5,
          burstAllowancePercent: Number(form.qosBurstAllowancePercent) || 20,
        };
      }
      await dmzApi.addMapping(payload);
      setModalOpen(false);
      setForm({ name: '', listenPort: '', targetHost: '', targetPort: '', qosEnabled: false, qosMaxBytesPerSecond: '', qosPerConnectionMaxBytesPerSecond: '', qosPriority: 5, qosBurstAllowancePercent: 20 });
      await fetchAll();
    } catch (err) {
      setFormError(err.response?.data?.message || err.message || 'Failed to add mapping.');
    } finally {
      setSubmitting(false);
    }
  };

  const handleRemove = async (name) => {
    if (!window.confirm(`Remove DMZ mapping "${name}"?`)) return;
    setRemovingName(name);
    try {
      await dmzApi.removeMapping(name);
      setMappings((prev) => prev.filter((m) => m.name !== name));
    } catch (err) {
      alert(err.response?.data?.message || err.message || 'Failed to remove mapping.');
    } finally {
      setRemovingName(null);
    }
  };

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">DMZ Proxy</h1>
          <p className="text-sm text-gray-500 mt-0.5">Manage port mappings and monitor proxy health</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={fetchAll}
            disabled={loading}
            className="flex items-center gap-2 px-4 py-2 bg-white border border-gray-300 text-gray-700 rounded-lg text-sm hover:bg-gray-50 transition-colors disabled:opacity-50"
          >
            <svg className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
            Refresh
          </button>
          <button
            onClick={() => { setModalOpen(true); setFormError(''); }}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
            </svg>
            Add Mapping
          </button>
        </div>
      </div>

      {/* Control Key Config */}
      <div className="bg-amber-50 border border-amber-200 rounded-xl p-4">
        <div className="flex items-start gap-3">
          <svg className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
              d="M15 7a2 2 0 012 2m4 0a6 6 0 01-7.743 5.743L11 17H9v2H7v2H4a1 1 0 01-1-1v-2.586a1 1 0 01.293-.707l5.964-5.964A6 6 0 1121 9z" />
          </svg>
          <div className="flex-1">
            <p className="text-sm font-medium text-amber-800">DMZ Internal Control Key</p>
            <p className="text-xs text-amber-700 mb-3">Required for DMZ proxy access. Set the X-Internal-Key value below.</p>
            <div className="flex gap-2">
              <input
                type="text"
                value={controlKey}
                onChange={(e) => setControlKey(e.target.value)}
                placeholder="Enter X-Internal-Key value"
                className="flex-1 px-3 py-2 border border-amber-300 rounded-lg text-sm font-mono bg-white focus:outline-none focus:ring-2 focus:ring-amber-400"
              />
              <button
                onClick={handleSaveKey}
                className="px-4 py-2 bg-amber-600 text-white rounded-lg text-sm font-medium hover:bg-amber-700 transition-colors"
              >
                Save & Refresh
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Health Status */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className={`rounded-xl border p-5 flex items-center gap-4 ${
          healthData ? 'bg-green-50 border-green-200' : 'bg-gray-50 border-gray-200'
        }`}>
          <div className={`w-10 h-10 rounded-full flex items-center justify-center ${
            healthData ? 'bg-green-100' : 'bg-gray-200'
          }`}>
            <span className={`w-4 h-4 rounded-full ${healthData ? 'bg-green-500' : 'bg-gray-400'}`} />
          </div>
          <div>
            <p className="text-sm font-semibold text-gray-800">Proxy Status</p>
            <p className={`text-xs ${healthData ? 'text-green-700' : 'text-gray-500'}`}>
              {healthData ? (healthData.status || 'Healthy') : (healthError || 'Unavailable')}
            </p>
          </div>
        </div>

        <div className="bg-white rounded-xl border border-gray-200 p-5 flex items-center gap-4">
          <div className="w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center text-blue-600 font-bold text-lg">
            {mappings.length}
          </div>
          <div>
            <p className="text-sm font-semibold text-gray-800">Active Mappings</p>
            <p className="text-xs text-gray-500">Port tunnels configured</p>
          </div>
        </div>

        <div className="bg-white rounded-xl border border-gray-200 p-5 flex items-center gap-4">
          <div className="w-10 h-10 rounded-lg bg-purple-50 flex items-center justify-center">
            <svg className="w-5 h-5 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
            </svg>
          </div>
          <div>
            <p className="text-sm font-semibold text-gray-800">DMZ Proxy</p>
            <p className="text-xs text-gray-500">Port 8088</p>
          </div>
        </div>
      </div>

      {/* Mappings Table */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-200">
        <div className="px-5 py-4 border-b border-gray-200">
          <h2 className="text-base font-semibold text-gray-900">Port Mappings</h2>
          <p className="text-sm text-gray-500">Live DMZ port forwarding rules</p>
        </div>

        {error && (
          <div className="mx-5 mt-4 bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">
            {error}
          </div>
        )}

        {loading ? (
          <div className="flex items-center justify-center py-16 text-gray-400">
            <svg className="animate-spin w-5 h-5 mr-2" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            Loading mappings...
          </div>
        ) : mappings.length === 0 ? (
          <div className="text-center py-16 text-gray-400">
            <svg className="w-12 h-12 mx-auto mb-3 text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
            </svg>
            <p className="text-sm">No DMZ mappings configured</p>
            {!error && (
              <button onClick={() => setModalOpen(true)} className="mt-3 text-blue-600 text-sm hover:underline">
                Add the first mapping
              </button>
            )}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="bg-gray-50 border-b border-gray-200">
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Name</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Listen Port</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Target Host</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Target Port</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">QoS</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Connections</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Status</th>
                  <th className="px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wider">Actions</th>
                </tr>
              </thead>
              <tbody>
                {mappings.map((mapping, idx) => {
                  const name = mapping.name || `mapping-${idx}`;
                  const active = mapping.active ?? mapping.enabled ?? mapping.status === 'ACTIVE' ?? true;
                  return (
                    <tr key={name} className="border-b border-gray-100 hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 text-sm font-medium text-gray-900">{name}</td>
                      <td className="px-4 py-3">
                        <span className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-mono font-medium bg-blue-50 text-blue-700">
                          :{mapping.listenPort || '—'}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-sm font-mono text-gray-600">{mapping.targetHost || '—'}</td>
                      <td className="px-4 py-3">
                        <span className="inline-flex items-center px-2 py-0.5 rounded-md text-xs font-mono font-medium bg-gray-100 text-gray-700">
                          :{mapping.targetPort || '—'}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        {mapping.qosPolicy?.enabled ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-700"
                            title={`Priority: ${mapping.qosPolicy.priority || 5}, Burst: ${mapping.qosPolicy.burstAllowancePercent || 0}%`}>
                            P{mapping.qosPolicy.priority || 5}
                            {mapping.qosPolicy.maxBytesPerSecond > 0 && ` / ${Math.round(mapping.qosPolicy.maxBytesPerSecond / 1048576)}MB/s`}
                          </span>
                        ) : (
                          <span className="text-xs text-gray-400">Off</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-sm text-gray-600">
                        {mapping.activeConnections ?? mapping.connections ?? '—'}
                      </td>
                      <td className="px-4 py-3">
                        <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${
                          active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                        }`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${active ? 'bg-green-500 animate-pulse' : 'bg-gray-400'}`} />
                          {active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <button
                          onClick={() => handleRemove(name)}
                          disabled={removingName === name}
                          className="px-3 py-1.5 rounded-lg text-xs font-medium bg-red-50 text-red-600 hover:bg-red-100 transition-colors disabled:opacity-50"
                        >
                          {removingName === name ? 'Removing...' : 'Remove'}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Add Mapping Modal */}
      <Modal
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        title="Add DMZ Port Mapping"
      >
        <form onSubmit={handleAdd} className="space-y-4">
          {formError && (
            <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg px-4 py-3 text-sm">
              {formError}
            </div>
          )}

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Mapping Name</label>
            <input
              type="text"
              name="name"
              value={form.name}
              onChange={handleChange}
              placeholder="sftp-prod"
              className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Listen Port</label>
            <input
              type="number"
              name="listenPort"
              value={form.listenPort}
              onChange={handleChange}
              placeholder="2222"
              min="1"
              max="65535"
              className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <p className="text-xs text-gray-400 mt-1">Port the DMZ proxy listens on externally</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Target Host</label>
            <input
              type="text"
              name="targetHost"
              value={form.targetHost}
              onChange={handleChange}
              placeholder="internal-sftp-server.local"
              className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <p className="text-xs text-gray-400 mt-1">Internal host to forward traffic to</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">Target Port</label>
            <input
              type="number"
              name="targetPort"
              value={form.targetPort}
              onChange={handleChange}
              placeholder="22"
              min="1"
              max="65535"
              className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* QoS Policy */}
          <div className="border-t border-gray-200 pt-3 mt-1">
            <div className="flex items-center gap-3 mb-3">
              <input type="checkbox" id="qosEnabled" checked={form.qosEnabled}
                onChange={e => setForm(prev => ({ ...prev, qosEnabled: e.target.checked }))}
                className="w-4 h-4 text-blue-600 rounded border-gray-300" />
              <label htmlFor="qosEnabled" className="block text-sm font-medium text-gray-700">Enable QoS Policy</label>
            </div>
            {form.qosEnabled && (
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Max Bandwidth (MB/s)</label>
                  <input type="number" min="0" name="qosMaxBytesPerSecond" value={form.qosMaxBytesPerSecond} onChange={handleChange}
                    placeholder="0 = unlimited"
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                </div>
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Per-Connection (MB/s)</label>
                  <input type="number" min="0" name="qosPerConnectionMaxBytesPerSecond" value={form.qosPerConnectionMaxBytesPerSecond} onChange={handleChange}
                    placeholder="0 = unlimited"
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                </div>
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Priority (1=High, 10=Low)</label>
                  <input type="number" min="1" max="10" name="qosPriority" value={form.qosPriority} onChange={handleChange}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                </div>
                <div>
                  <label className="block text-xs text-gray-500 mb-1">Burst Allowance (%)</label>
                  <input type="number" min="0" max="100" name="qosBurstAllowancePercent" value={form.qosBurstAllowancePercent} onChange={handleChange}
                    className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500" />
                </div>
              </div>
            )}
          </div>

          <div className="flex gap-3 pt-2">
            <button type="button" onClick={() => setModalOpen(false)}
              className="flex-1 px-4 py-2.5 border border-gray-300 text-gray-700 rounded-lg text-sm font-medium hover:bg-gray-50 transition-colors">
              Cancel
            </button>
            <button type="submit" disabled={submitting}
              className="flex-1 px-4 py-2.5 bg-blue-600 text-white rounded-lg text-sm font-medium hover:bg-blue-700 disabled:bg-blue-400 transition-colors">
              {submitting ? 'Adding...' : 'Add Mapping'}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
};

export default DmzProxy;
