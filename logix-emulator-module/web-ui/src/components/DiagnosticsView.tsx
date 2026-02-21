import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Activity, AlertCircle, RefreshCw } from 'lucide-react'
import './DiagnosticsView.css'

interface DeviceInfo {
  name: string
  status: string
  enabled: boolean
  fileName: string
  parserType: string
  simulationEnabled: boolean
}

interface DeviceDetail {
  deviceName: string
  status: string
  fileName: string
  parserType: string
  hasFile: boolean
  fileSize: number
  simulationEnabled: boolean
  enabled: boolean
}

function DiagnosticsView() {
  const [devices, setDevices] = useState<DeviceDetail[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const timerRef = useRef<number | null>(null)

  const fetchData = useCallback(async (isManual = false) => {
    try {
      if (isManual) setRefreshing(true)
      setError(null)

      const res = await fetch('/data/logixemulator/devices', {
        credentials: 'same-origin',
        signal: AbortSignal.timeout(5000),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      const deviceList: DeviceInfo[] = data.devices || []

      const details = await Promise.allSettled(
        deviceList.map(d =>
          fetch(`/data/logixemulator/device/${encodeURIComponent(d.name)}/status`, {
            credentials: 'same-origin',
            signal: AbortSignal.timeout(5000),
          }).then(r => r.ok ? r.json() as Promise<DeviceDetail> : null)
        )
      )

      const results: DeviceDetail[] = []
      for (let i = 0; i < deviceList.length; i++) {
        const result = details[i]
        if (result.status === 'fulfilled' && result.value) {
          results.push(result.value)
        } else {
          results.push({
            deviceName: deviceList[i].name,
            status: deviceList[i].status,
            fileName: deviceList[i].fileName,
            parserType: deviceList[i].parserType,
            hasFile: false,
            fileSize: 0,
            simulationEnabled: deviceList[i].simulationEnabled,
            enabled: deviceList[i].enabled,
          })
        }
      }
      setDevices(results)
    } catch (err) {
      setError('Failed to load diagnostics')
      console.error('[DiagnosticsView] Fetch error:', err)
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
    timerRef.current = window.setInterval(() => fetchData(), 30000)
    return () => {
      if (timerRef.current !== null) clearInterval(timerRef.current)
    }
  }, [fetchData])

  const getStatusColor = (status: string) => {
    const s = status?.toLowerCase() || ''
    if (s.includes('connect') || s.includes('running')) return '#a6e3a1'
    if (s.includes('fault') || s.includes('error')) return '#f38ba8'
    if (s.includes('disabled')) return '#6c7086'
    return '#fab387'
  }

  const formatFileSize = (bytes: number) => {
    if (!bytes) return '--'
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }

  return (
    <div className="diagnostics-view">
      <div className="diagnostics-header">
        <div className="diagnostics-header-left">
          <Activity size={20} />
          <div>
            <h2>Diagnostics</h2>
            <p>Device health and configuration status</p>
          </div>
        </div>
        <div className="diagnostics-header-actions">
          <button
            className="diagnostics-refresh-btn"
            onClick={() => fetchData(true)}
            disabled={refreshing}
            title="Refresh"
          >
            <RefreshCw size={14} className={refreshing ? 'spinning' : ''} />
          </button>
        </div>
      </div>

      {error && (
        <div className="diagnostics-error">
          <AlertCircle size={16} />
          <span>{error}</span>
          <button className="diagnostics-error-retry" onClick={() => fetchData(true)}>Retry</button>
        </div>
      )}

      {loading ? (
        <div className="diagnostics-loading">Loading device diagnostics...</div>
      ) : devices.length === 0 ? (
        <div className="diagnostics-empty">
          No devices configured. Create devices in Config &rarr; OPC UA &rarr; Device Connections.
        </div>
      ) : (
        <div className="diagnostics-grid">
          {devices.map(device => (
            <div key={device.deviceName} className="diagnostics-card">
              <div className="diagnostics-card-header">
                <span
                  className="diagnostics-status-dot"
                  style={{ background: getStatusColor(device.status) }}
                />
                <span className="diagnostics-device-name">{device.deviceName}</span>
              </div>
              <div className="diagnostics-card-body">
                <div className="diagnostics-field">
                  <span className="diagnostics-field-label">Status</span>
                  <span className="diagnostics-field-value" style={{ color: getStatusColor(device.status) }}>
                    {device.status || 'Unknown'}
                  </span>
                </div>
                <div className="diagnostics-field">
                  <span className="diagnostics-field-label">File</span>
                  <span className="diagnostics-field-value">
                    {device.hasFile ? device.fileName : 'No file'}
                  </span>
                </div>
                <div className="diagnostics-field">
                  <span className="diagnostics-field-label">Parser</span>
                  <span className="diagnostics-field-value">{device.parserType || '--'}</span>
                </div>
                <div className="diagnostics-field">
                  <span className="diagnostics-field-label">File Size</span>
                  <span className="diagnostics-field-value">{formatFileSize(device.fileSize)}</span>
                </div>
                <div className="diagnostics-field">
                  <span className="diagnostics-field-label">Simulation</span>
                  <span className={`diagnostics-field-value ${device.simulationEnabled ? 'active' : ''}`}>
                    {device.simulationEnabled ? 'Active' : 'Off'}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default DiagnosticsView
