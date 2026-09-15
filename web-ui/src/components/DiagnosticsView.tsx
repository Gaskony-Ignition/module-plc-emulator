import { useState, useEffect, useCallback, useRef } from 'react'
import { Activity, AlertCircle, RefreshCw, Pause, Play, ArrowDownToLine } from 'lucide-react'
import PageHeader from './PageHeader'
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
  epochMs?: number
  level: string
  source: string
  logger: string
  message: string
}

type LevelFilter = 'ALL' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG'
const LEVEL_FILTERS: LevelFilter[] = ['ALL', 'ERROR', 'WARN', 'INFO', 'DEBUG']
const LOG_POLL_MS = 10_000
const MAX_LOG_ENTRIES = 500 // cap in-memory entries

function DiagnosticsView() {
  const [devices, setDevices]       = useState<DeviceDetail[]>([])
  const [loading, setLoading]       = useState(true)
  const [error, setError]           = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const [moduleLogs, setModuleLogs] = useState<LogEntry[]>([])
  const [logLevel, setLogLevel] = useState<LevelFilter>('ALL')
  const [logPaused, setLogPaused] = useState(false)
  const [logAutoScroll, setLogAutoScroll] = useState(true)

  const allLogsRef      = useRef<LogEntry[]>([])
  const lastEventIdRef  = useRef<number>(0)
  const logBodyRef      = useRef<HTMLDivElement>(null)

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
    if (logPaused) return
    try {
      let url = `${API.SYSTEM_LOGS(200)}&moduleOnly=true`
      if (lastEventIdRef.current > 0) {
        url += `&after=${lastEventIdRef.current}`
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
    }
  }, [logPaused])

  // Auto-scroll to bottom when new entries arrive (chronological order)
  useEffect(() => {
    if (logAutoScroll && logBodyRef.current) {
      logBodyRef.current.scrollTop = logBodyRef.current.scrollHeight
    }
  }, [moduleLogs, logAutoScroll])

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
    logsTimerRef.current = window.setInterval(() => fetchLogs(), LOG_POLL_MS)
    return () => {
      if (logsTimerRef.current !== null) clearInterval(logsTimerRef.current)
    }
  }, [fetchLogs])

  // ── Filtered logs ────────────────────────────────────────────────────────

  const filteredLogs = logLevel === 'ALL'
    ? moduleLogs
    : moduleLogs.filter(e => e.level === logLevel)

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="diagnostics-view">
      {/* Header */}
      <PageHeader icon={Activity} title="Diagnostics" subtitle="Device health and module logs">
        <button className="diagnostics-refresh-btn" onClick={() => { fetchData(true); fetchLogs() }} disabled={refreshing} title="Refresh">
          <RefreshCw size={14} className={refreshing ? 'spinning' : ''} />
        </button>
      </PageHeader>

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

      {/* ---- Module Logs ---- */}
      <div className="diag-panel diag-logs-panel">
        <div className="diag-logs-header">
          <span className="diag-logs-header__title">Module Logs</span>
          <div className="diag-logs-controls">
            <div className="diag-logs-filters">
              {LEVEL_FILTERS.map(lv => (
                <button
                  key={lv}
                  className={`diag-logs-pill ${logLevel === lv ? 'diag-logs-pill--active' : ''}`}
                  onClick={() => setLogLevel(lv)}
                >
                  {lv}
                </button>
              ))}
            </div>
            <button
              className={`diag-logs-icon-btn ${logPaused ? 'diag-logs-icon-btn--warning' : ''}`}
              onClick={() => setLogPaused((v) => !v)}
              title={logPaused ? 'Resume live logs' : 'Pause live logs'}
            >
              {logPaused ? <Play size={12} /> : <Pause size={12} />}
            </button>
            <button
              className={`diag-logs-icon-btn ${logAutoScroll ? 'diag-logs-icon-btn--active' : ''}`}
              onClick={() => setLogAutoScroll((v) => !v)}
              title={logAutoScroll ? 'Auto-scroll enabled' : 'Auto-scroll disabled'}
            >
              <ArrowDownToLine size={12} />
            </button>
          </div>
        </div>

        <div className="diag-logs-body" ref={logBodyRef}>
          {filteredLogs.length === 0 ? (
            <div className="diag-logs-empty">No log entries found</div>
          ) : (
            filteredLogs.map((entry, idx) => (
              <div key={entry.id || idx} className="diag-logs-entry">
                <span className="diag-logs-entry__ts">
                  {entry.epochMs
                    ? new Date(entry.epochMs).toLocaleString()
                    : entry.timestamp}
                </span>
                <span className={`diag-logs-entry__level diag-logs-entry__level--${entry.level || 'INFO'}`}>
                  {entry.level || 'INFO'}
                </span>
                <span className="diag-logs-entry__msg">{entry.message}</span>
              </div>
            ))
          )}
        </div>

        <div className="diag-logs-status">
          <span>{filteredLogs.length} entries (module only)</span>
          <span>{logPaused ? 'Paused' : `Auto-refresh: ${LOG_POLL_MS / 1000}s`}</span>
        </div>
      </div>
    </div>
  )
}

export default DiagnosticsView
