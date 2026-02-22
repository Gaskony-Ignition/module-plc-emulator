import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Activity, AlertCircle, RefreshCw, Trash2, ChevronsDown } from 'lucide-react'
import { API } from '../constants/api'
import { apiGet, apiFetch } from '../utils/apiClient'
import { DeviceInfo } from '../types/device'
import { getStatusColor } from '../utils/statusColor'
import { formatBytes } from '../utils/format'
import './DiagnosticsView.css'

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

interface LogEntry {
  id: string
  timestamp: string
  level: string
  source: string
  logger: string
  message: string
}

type LevelFilter = '' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG'

const LOG_POLL_INTERVAL = 5000   // 5 s — fast enough to feel live
const MAX_LOG_ENTRIES   = 500    // cap in-memory entries

function DiagnosticsView() {
  const [devices, setDevices]       = useState<DeviceDetail[]>([])
  const [loading, setLoading]       = useState(true)
  const [error, setError]           = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const [moduleLogs, setModuleLogs] = useState<LogEntry[]>([])
  const [logsLoading, setLogsLoading] = useState(true)
  const [levelFilter, setLevelFilter] = useState<LevelFilter>('')
  const [autoScroll, setAutoScroll]   = useState(true)

  const allLogsRef      = useRef<LogEntry[]>([])
  const lastEventIdRef  = useRef<number>(0)
  const logsBodyRef     = useRef<HTMLDivElement>(null)

  const timerRef        = useRef<number | null>(null)
  const logsTimerRef    = useRef<number | null>(null)

  // ── Device diagnostics ────────────────────────────────────────────────────

  const fetchData = useCallback(async (isManual = false) => {
    try {
      if (isManual) setRefreshing(true)
      setError(null)

      const data = await apiGet<{ devices: DeviceInfo[] }>(API.DEVICES)
      const deviceList: DeviceInfo[] = data.devices || []

      const details = await Promise.allSettled(
        deviceList.map(d =>
          apiFetch(API.DEVICE_STATUS(d.name)).then(r => r.ok ? r.json() as Promise<DeviceDetail> : null)
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

  // ── Log fetching — Camera Driver style ───────────────────────────────────
  // Reads from system_logs.idb via the backend (moduleOnly=true filters to
  // com.inductiveautomation.logixemulator.*). Uses event_id for incremental
  // polling so only new entries are fetched on each tick.

  const fetchLogs = useCallback(async () => {
    try {
      const level = levelFilter
      let url = `${API.SYSTEM_LOGS(200)}&moduleOnly=true`
      if (lastEventIdRef.current > 0) {
        url += `&after=${lastEventIdRef.current}`
      }
      if (level) {
        url += `&level=${encodeURIComponent(level)}`
      }

      const res = await apiFetch(url)
      if (!res.ok) return
      const data = await res.json()

      if (data.success) {
        const newEntries: LogEntry[] = data.entries || []

        // Update the last-seen event ID for next incremental poll
        if (data.lastEventId && data.lastEventId > lastEventIdRef.current) {
          lastEventIdRef.current = data.lastEventId
        } else if (newEntries.length > 0) {
          // Fallback: derive from last entry id if backend didn't return lastEventId
          const lastId = Number(newEntries[newEntries.length - 1].id)
          if (!isNaN(lastId) && lastId > lastEventIdRef.current) {
            lastEventIdRef.current = lastId
          }
        }

        if (newEntries.length > 0) {
          allLogsRef.current = [...allLogsRef.current, ...newEntries].slice(-MAX_LOG_ENTRIES)
          setModuleLogs([...allLogsRef.current])
        }
      }
    } catch {
      // silently ignore — log panel is non-critical
    } finally {
      setLogsLoading(false)
    }
  }, [levelFilter])

  // Auto-scroll to bottom when new entries arrive
  useEffect(() => {
    if (autoScroll && logsBodyRef.current) {
      logsBodyRef.current.scrollTop = logsBodyRef.current.scrollHeight
    }
  }, [moduleLogs, autoScroll])

  // ── Device poll ───────────────────────────────────────────────────────────

  useEffect(() => {
    fetchData()
    timerRef.current = window.setInterval(() => fetchData(), 30000)
    return () => {
      if (timerRef.current !== null) clearInterval(timerRef.current)
    }
  }, [fetchData])

  // ── Log poll ──────────────────────────────────────────────────────────────

  useEffect(() => {
    fetchLogs()
    logsTimerRef.current = window.setInterval(() => fetchLogs(), LOG_POLL_INTERVAL)
    return () => {
      if (logsTimerRef.current !== null) clearInterval(logsTimerRef.current)
    }
  }, [fetchLogs])

  // ── Level filter change — reset and full re-fetch ─────────────────────────
  // Resetting the refs here (synchronously, before the next render) ensures
  // that when the useEffect re-fires (because fetchLogs recreates due to
  // levelFilter changing), it starts from event_id=0 with an empty list.

  const handleLevelChange = (level: LevelFilter) => {
    allLogsRef.current = []
    lastEventIdRef.current = 0
    setModuleLogs([])
    setLogsLoading(true)
    setLevelFilter(level) // triggers fetchLogs to recreate → useEffect restarts interval
  }

  // ── Clear logs ────────────────────────────────────────────────────────────

  const handleClearLogs = () => {
    allLogsRef.current = []
    lastEventIdRef.current = 0
    setModuleLogs([])
  }

  // ── Level badge class ─────────────────────────────────────────────────────

  const getLevelClass = (level: string) => {
    switch ((level || '').toUpperCase()) {
      case 'ERROR': case 'FATAL': return 'diag-log-error'
      case 'WARN':                return 'diag-log-warn'
      case 'INFO':                return 'diag-log-info'
      case 'DEBUG': case 'TRACE': return 'diag-log-debug'
      default:                    return ''
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="diagnostics-view">
      {/* Header */}
      <div className="diagnostics-header">
        <div className="diagnostics-header-left">
          <Activity size={20} />
          <div>
            <h2>Diagnostics</h2>
            <p>Device health and module logs</p>
          </div>
        </div>
        <div className="diagnostics-header-actions">
          <button
            className="diagnostics-refresh-btn"
            onClick={() => { fetchData(true); handleClearLogs(); fetchLogs() }}
            disabled={refreshing}
            title="Refresh"
          >
            <RefreshCw size={14} className={refreshing ? 'spinning' : ''} />
          </button>
        </div>
      </div>

      {/* Error banner */}
      {error && (
        <div className="diagnostics-error">
          <AlertCircle size={16} />
          <span>{error}</span>
          <button className="diagnostics-error-retry" onClick={() => fetchData(true)}>Retry</button>
        </div>
      )}

      {/* Device cards */}
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
                  <span className="diagnostics-field-value">{formatBytes(device.fileSize) || '--'}</span>
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

      {/* Module Logs */}
      <div className="diagnostics-logs-section">
        <div className="diagnostics-logs-header">
          <span className="diagnostics-section-title">Module Logs</span>
          <div className="diag-log-controls">
            {/* Level filter pills */}
            <div className="diag-level-pills">
              {(['', 'ERROR', 'WARN', 'INFO', 'DEBUG'] as LevelFilter[]).map(l => (
                <button
                  key={l || 'ALL'}
                  className={`diag-level-pill ${levelFilter === l ? 'active' : ''} ${l ? getLevelClass(l) : ''}`}
                  onClick={() => handleLevelChange(l)}
                >
                  {l || 'ALL'}
                </button>
              ))}
            </div>

            {/* Auto-scroll toggle */}
            <button
              className={`diag-control-btn ${autoScroll ? 'active' : ''}`}
              title={autoScroll ? 'Auto-scroll on' : 'Auto-scroll off'}
              onClick={() => setAutoScroll(v => !v)}
            >
              <ChevronsDown size={13} />
            </button>

            {/* Clear */}
            <button
              className="diag-control-btn"
              title="Clear log panel"
              onClick={handleClearLogs}
            >
              <Trash2 size={13} />
            </button>
          </div>
        </div>

        <div className="diagnostics-logs-table">
          <div className="diagnostics-logs-thead">
            <span className="diag-log-col-time">Timestamp</span>
            <span className="diag-log-col-level">Level</span>
            <span className="diag-log-col-source">Logger</span>
            <span className="diag-log-col-msg">Message</span>
          </div>
          <div className="diagnostics-logs-body" ref={logsBodyRef}>
            {logsLoading ? (
              <div className="diagnostics-logs-empty">Loading module logs…</div>
            ) : moduleLogs.length === 0 ? (
              <div className="diagnostics-logs-empty">
                No module log entries found
                {levelFilter && ` for level ${levelFilter}`}
              </div>
            ) : (
              moduleLogs.map(entry => (
                <div
                  key={entry.id || entry.timestamp + entry.message}
                  className={`diagnostics-log-row ${getLevelClass(entry.level)}`}
                >
                  <span className="diag-log-col-time">{entry.timestamp}</span>
                  <span className={`diag-log-col-level diag-log-badge ${getLevelClass(entry.level)}`}>
                    {entry.level}
                  </span>
                  <span className="diag-log-col-source" title={entry.logger || entry.source}>
                    {entry.source || entry.logger}
                  </span>
                  <span className="diag-log-col-msg" title={entry.message}>{entry.message}</span>
                </div>
              ))
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

export default DiagnosticsView
