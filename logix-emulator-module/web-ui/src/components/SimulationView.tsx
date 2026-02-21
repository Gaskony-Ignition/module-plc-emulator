import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  Zap, HardDrive, RefreshCw, Play, Square,
  AlertCircle, ChevronDown, ChevronRight
} from 'lucide-react'
import './SimulationView.css'

interface DeviceInfo {
  name: string
  status: string
  enabled: boolean
  simulationEnabled: boolean
}

interface SimulatedTag {
  path: string
  pattern: string
}

interface DeviceSimState {
  simulationEngineAvailable: boolean
  simulatedTags: SimulatedTag[]
  count: number
}

function SimulationView() {
  const [devices, setDevices] = useState<DeviceInfo[]>([])
  const [selectedDevice, setSelectedDevice] = useState<string | null>(null)
  const [simState, setSimState] = useState<DeviceSimState | null>(null)
  const [loading, setLoading] = useState(true)
  const [simLoading, setSimLoading] = useState(false)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [expandedDevice, setExpandedDevice] = useState<string | null>(null)
  const timerRef = useRef<number | null>(null)

  const fetchDevices = useCallback(async () => {
    try {
      const res = await fetch('/data/logixemulator/devices', {
        credentials: 'same-origin',
        signal: AbortSignal.timeout(5000),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      setDevices(data.devices || [])
      setError(null)
    } catch {
      setError('Failed to load devices')
    } finally {
      setLoading(false)
    }
  }, [])

  const fetchSimState = useCallback(async (deviceName: string) => {
    setSimLoading(true)
    try {
      const res = await fetch(`/data/logixemulator/device/${encodeURIComponent(deviceName)}/tags/simulated`, {
        credentials: 'same-origin',
        signal: AbortSignal.timeout(5000),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      setSimState({
        simulationEngineAvailable: data.simulationEngineAvailable || false,
        simulatedTags: data.simulatedTags || [],
        count: data.count || 0,
      })
    } catch {
      setSimState(null)
    } finally {
      setSimLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchDevices()
    timerRef.current = window.setInterval(fetchDevices, 15000)
    return () => {
      if (timerRef.current !== null) clearInterval(timerRef.current)
    }
  }, [fetchDevices])

  useEffect(() => {
    if (selectedDevice) {
      fetchSimState(selectedDevice)
    } else {
      setSimState(null)
    }
  }, [selectedDevice, fetchSimState])

  const handleEnableAll = useCallback(async (deviceName: string) => {
    setActionLoading('enableAll')
    try {
      const res = await fetch(`/data/logixemulator/device/${encodeURIComponent(deviceName)}/simulation/all`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        body: JSON.stringify({ enabled: true }),
      })
      const data = await res.json()
      if (data.success) {
        await fetchSimState(deviceName)
      }
    } catch {
      // silently ignore
    } finally {
      setActionLoading(null)
    }
  }, [fetchSimState])

  const handleDisableAll = useCallback(async (deviceName: string) => {
    setActionLoading('disableAll')
    try {
      const res = await fetch(`/data/logixemulator/device/${encodeURIComponent(deviceName)}/simulation/all`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        body: JSON.stringify({ enabled: false }),
      })
      const data = await res.json()
      if (data.success) {
        await fetchSimState(deviceName)
      }
    } catch {
      // silently ignore
    } finally {
      setActionLoading(null)
    }
  }, [fetchSimState])

  const handleToggleTag = useCallback(async (deviceName: string, tagPath: string) => {
    setActionLoading(tagPath)
    try {
      const res = await fetch(`/data/logixemulator/device/${encodeURIComponent(deviceName)}/tag/simulate`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        body: JSON.stringify({ tagPath }),
      })
      const data = await res.json()
      if (data.success) {
        await fetchSimState(deviceName)
      }
    } catch {
      // silently ignore
    } finally {
      setActionLoading(null)
    }
  }, [fetchSimState])

  const handleScopeAction = useCallback(async (deviceName: string, scope: string, enabled: boolean) => {
    setActionLoading(`scope-${scope}-${enabled}`)
    try {
      const res = await fetch(`/data/logixemulator/device/${encodeURIComponent(deviceName)}/simulation/scope`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        body: JSON.stringify({ scope, enabled }),
      })
      const data = await res.json()
      if (data.success) {
        await fetchSimState(deviceName)
      }
    } catch {
      // silently ignore
    } finally {
      setActionLoading(null)
    }
  }, [fetchSimState])

  const getStatusColor = (status: string) => {
    const s = status.toLowerCase()
    if (s === 'connected' || s === 'running') return '#a6e3a1'
    if (s === 'fault' || s === 'error') return '#f38ba8'
    if (s === 'disabled') return '#6c7086'
    return '#fab387'
  }

  const simEnabledDevices = devices.filter(d => d.simulationEnabled)

  if (loading) {
    return (
      <div className="simulation-view">
        <div className="sim-loading">Loading devices...</div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="simulation-view">
        <div className="sim-error">
          <AlertCircle size={16} />
          <span>{error}</span>
          <button className="sim-retry-btn" onClick={fetchDevices}>Retry</button>
        </div>
      </div>
    )
  }

  return (
    <div className="simulation-view">
      <div className="sim-header">
        <div className="sim-header-left">
          <Zap size={20} />
          <div>
            <h2>Simulation Control <span className="sim-beta-badge">Beta</span></h2>
            <p>Manage tag simulation across devices</p>
          </div>
        </div>
        <div className="sim-header-actions">
          <button className="sim-refresh-btn" onClick={fetchDevices} title="Refresh devices">
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      <div className="sim-summary">
        <div className="sim-summary-stat">
          <HardDrive size={14} />
          <span>{devices.length} device{devices.length !== 1 ? 's' : ''}</span>
        </div>
        <div className="sim-summary-stat sim-active">
          <Zap size={14} />
          <span>{simEnabledDevices.length} simulation enabled</span>
        </div>
      </div>

      <div className="sim-device-list">
        {devices.length === 0 ? (
          <div className="sim-empty">
            <HardDrive size={32} />
            <p>No devices configured</p>
            <p className="sim-empty-detail">Create devices in Config &rarr; OPC UA &rarr; Device Connections</p>
          </div>
        ) : (
          devices.map(device => {
            const isSelected = selectedDevice === device.name
            const isExpanded = expandedDevice === device.name

            return (
              <div key={device.name} className={`sim-device-card ${isSelected ? 'selected' : ''}`}>
                <div
                  className="sim-device-header"
                  onClick={() => {
                    if (isSelected) {
                      setSelectedDevice(null)
                      setExpandedDevice(null)
                    } else {
                      setSelectedDevice(device.name)
                      setExpandedDevice(device.name)
                    }
                  }}
                >
                  <div className="sim-device-info">
                    <span className="sim-device-expand">
                      {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </span>
                    <span className="sim-status-dot" style={{ background: getStatusColor(device.status) }} />
                    <span className="sim-device-name">{device.name}</span>
                    <span className="sim-device-status">{device.status}</span>
                  </div>
                  <div className="sim-device-badges">
                    {device.simulationEnabled && (
                      <span className="sim-badge sim-badge-active">
                        <Zap size={10} /> SIM
                      </span>
                    )}
                    {!device.enabled && (
                      <span className="sim-badge sim-badge-disabled">DISABLED</span>
                    )}
                  </div>
                </div>

                {isExpanded && isSelected && (
                  <div className="sim-device-body">
                    {simLoading ? (
                      <div className="sim-device-loading">Loading simulation state...</div>
                    ) : !device.simulationEnabled ? (
                      <div className="sim-device-notice">
                        <AlertCircle size={14} />
                        <span>Simulation is not enabled for this device. Enable it in the device configuration settings.</span>
                      </div>
                    ) : !simState?.simulationEngineAvailable ? (
                      <div className="sim-device-notice">
                        <AlertCircle size={14} />
                        <span>Simulation engine not available. The device may not be running.</span>
                      </div>
                    ) : (
                      <>
                        <div className="sim-controls">
                          <div className="sim-controls-label">
                            <Zap size={14} />
                            <span>{simState.count} tag{simState.count !== 1 ? 's' : ''} simulating</span>
                          </div>
                          <div className="sim-controls-actions">
                            <button
                              className="sim-ctrl-btn sim-ctrl-enable"
                              onClick={() => handleEnableAll(device.name)}
                              disabled={actionLoading !== null}
                            >
                              <Play size={12} />
                              Enable All
                            </button>
                            <button
                              className="sim-ctrl-btn sim-ctrl-disable"
                              onClick={() => handleDisableAll(device.name)}
                              disabled={actionLoading !== null}
                            >
                              <Square size={12} />
                              Disable All
                            </button>
                          </div>
                        </div>

                        <div className="sim-scope-controls">
                          <span className="sim-scope-label">By scope:</span>
                          <div className="sim-scope-btns">
                            <button
                              className="sim-scope-btn"
                              onClick={() => handleScopeAction(device.name, 'Controller:Global', true)}
                              disabled={actionLoading !== null}
                            >
                              <Play size={10} /> Global
                            </button>
                            <button
                              className="sim-scope-btn"
                              onClick={() => handleScopeAction(device.name, 'Programs', true)}
                              disabled={actionLoading !== null}
                            >
                              <Play size={10} /> Programs
                            </button>
                            <button
                              className="sim-scope-btn sim-scope-stop"
                              onClick={() => handleScopeAction(device.name, 'Controller:Global', false)}
                              disabled={actionLoading !== null}
                            >
                              <Square size={10} /> Stop Global
                            </button>
                            <button
                              className="sim-scope-btn sim-scope-stop"
                              onClick={() => handleScopeAction(device.name, 'Programs', false)}
                              disabled={actionLoading !== null}
                            >
                              <Square size={10} /> Stop Programs
                            </button>
                          </div>
                        </div>

                        {simState.simulatedTags.length > 0 && (
                          <div className="sim-tag-list">
                            <div className="sim-tag-list-header">
                              <span>Simulated Tags ({simState.simulatedTags.length})</span>
                            </div>
                            <div className="sim-tag-list-body">
                              {simState.simulatedTags.map(tag => (
                                <div key={tag.path} className="sim-tag-item">
                                  <span className="sim-tag-path" title={tag.path}>
                                    {tag.path.split('/').pop() || tag.path}
                                  </span>
                                  <span className="sim-tag-pattern">{tag.pattern || 'sine'}</span>
                                  <button
                                    className="sim-tag-stop-btn"
                                    onClick={() => handleToggleTag(device.name, tag.path)}
                                    disabled={actionLoading === tag.path}
                                    title="Stop simulation for this tag"
                                  >
                                    <Square size={10} />
                                  </button>
                                </div>
                              ))}
                            </div>
                          </div>
                        )}

                        {simState.simulatedTags.length === 0 && (
                          <div className="sim-no-tags">
                            No tags are currently being simulated. Use the controls above or the Tag Browser to enable simulation on individual tags.
                          </div>
                        )}
                      </>
                    )}
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}

export default SimulationView
