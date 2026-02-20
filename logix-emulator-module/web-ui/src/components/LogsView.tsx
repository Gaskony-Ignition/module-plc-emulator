import React, { useState, useEffect, useCallback, useRef } from 'react'
import { AlertCircle, RefreshCw, Download, Trash2 } from 'lucide-react'
import './LogsView.css'

interface LogEntry {
  timestamp: string
  level: string
  source: string
  message: string
}

type LogLevel = 'ALL' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG'

function LogsView() {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [levelFilter, setLevelFilter] = useState<LogLevel>('ALL')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [autoScroll, setAutoScroll] = useState(true)
  const contentRef = useRef<HTMLDivElement>(null)
  const timerRef = useRef<number | null>(null)
  const userScrolledRef = useRef(false)

  const fetchLogs = useCallback(async () => {
    try {
      setError(null)
      const res = await fetch('/data/logixemulator/system/logs?limit=200', {
        credentials: 'same-origin',
        signal: AbortSignal.timeout(10000),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      if (data.success && data.entries) {
        setEntries(data.entries)
      } else if (!data.success) {
        setError(data.error || 'Failed to load logs')
      }
    } catch (err) {
      setError('Failed to connect to log endpoint')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchLogs()
    timerRef.current = window.setInterval(fetchLogs, 5000)
    return () => {
      if (timerRef.current !== null) clearInterval(timerRef.current)
    }
  }, [fetchLogs])

  // Auto-scroll to bottom when new entries arrive
  useEffect(() => {
    if (autoScroll && !userScrolledRef.current && contentRef.current) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight
    }
  }, [entries, autoScroll])

  const handleScroll = useCallback(() => {
    if (!contentRef.current) return
    const { scrollTop, scrollHeight, clientHeight } = contentRef.current
    const atBottom = scrollHeight - scrollTop - clientHeight < 30
    userScrolledRef.current = !atBottom
    if (atBottom) setAutoScroll(true)
  }, [])

  const resumeAutoScroll = useCallback(() => {
    userScrolledRef.current = false
    setAutoScroll(true)
    if (contentRef.current) {
      contentRef.current.scrollTop = contentRef.current.scrollHeight
    }
  }, [])

  const clearLogs = useCallback(() => {
    setEntries([])
  }, [])

  const getLevelClass = (level: string) => {
    switch (level.toUpperCase()) {
      case 'ERROR': case 'FATAL': return 'log-level-error'
      case 'WARN': return 'log-level-warn'
      case 'INFO': return 'log-level-info'
      case 'DEBUG': return 'log-level-debug'
      default: return ''
    }
  }

  const filteredEntries = levelFilter === 'ALL'
    ? entries
    : entries.filter(e => {
        if (levelFilter === 'ERROR') return e.level === 'ERROR' || e.level === 'FATAL'
        return e.level.toUpperCase() === levelFilter
      })

  const exportLogs = () => {
    const json = JSON.stringify(filteredEntries, null, 2)
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `logix-emulator-logs-${new Date().toISOString().slice(0, 19)}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div className="logs-view">
      <div className="logs-header">
        <div>
          <h2>Gateway Logs</h2>
          <p>Real-time log viewer for Ignition Gateway events</p>
        </div>
        <div className="logs-toolbar">
          <div className="logs-level-filters">
            {(['ALL', 'ERROR', 'WARN', 'INFO', 'DEBUG'] as LogLevel[]).map(level => (
              <button
                key={level}
                className={`logs-level-btn ${levelFilter === level ? 'active' : ''} ${level !== 'ALL' ? getLevelClass(level) : ''}`}
                onClick={() => setLevelFilter(level)}
              >
                {level}
              </button>
            ))}
          </div>
          <button className="logs-action-btn" onClick={exportLogs} title="Export logs">
            <Download size={14} />
          </button>
          <button className="logs-action-btn" onClick={clearLogs} title="Clear logs">
            <Trash2 size={14} />
          </button>
          <button className="logs-action-btn" onClick={fetchLogs} title="Refresh">
            <RefreshCw size={14} />
          </button>
        </div>
      </div>

      {error && (
        <div className="logs-error">
          <AlertCircle size={14} />
          <span>{error}</span>
        </div>
      )}

      <div className="logs-table-header">
        <span className="logs-col-time">Timestamp</span>
        <span className="logs-col-level">Level</span>
        <span className="logs-col-source">Source</span>
        <span className="logs-col-message">Message</span>
      </div>

      <div className="logs-content" ref={contentRef} onScroll={handleScroll}>
        {loading ? (
          <div className="logs-empty">Loading logs...</div>
        ) : filteredEntries.length === 0 ? (
          <div className="logs-empty">No log entries{levelFilter !== 'ALL' ? ` matching level: ${levelFilter}` : ''}</div>
        ) : (
          filteredEntries.map((entry, i) => (
            <div key={i} className={`log-entry ${getLevelClass(entry.level)}`}>
              <span className="logs-col-time">{entry.timestamp}</span>
              <span className={`logs-col-level log-badge ${getLevelClass(entry.level)}`}>{entry.level}</span>
              <span className="logs-col-source" title={entry.source}>{entry.source}</span>
              <span className="logs-col-message" title={entry.message}>{entry.message}</span>
            </div>
          ))
        )}
      </div>

      {userScrolledRef.current && !autoScroll && (
        <button className="logs-resume-btn" onClick={resumeAutoScroll}>
          Resume auto-scroll
        </button>
      )}
    </div>
  )
}

export default LogsView
