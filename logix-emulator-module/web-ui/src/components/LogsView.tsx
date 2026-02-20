import React, { useState, useEffect, useCallback, useRef } from 'react'
import { AlertCircle, RefreshCw, Download, Trash2, Pause, Play, Search, X } from 'lucide-react'
import './LogsView.css'

interface LogEntry {
  id: string
  timestamp: string
  level: string
  source: string
  logger: string
  message: string
}

type LogLevel = 'ALL' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG'

const POLL_INTERVAL = 3000
const MAX_ENTRIES = 500
const DEFAULT_FILTER = 'logixemulator'

function LogsView() {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [levelFilter, setLevelFilter] = useState<LogLevel>('ALL')
  const [textFilter, setTextFilter] = useState(DEFAULT_FILTER)
  const [appliedFilter, setAppliedFilter] = useState(DEFAULT_FILTER)
  const [paused, setPaused] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [autoScroll, setAutoScroll] = useState(true)
  const contentRef = useRef<HTMLDivElement>(null)
  const timerRef = useRef<number | null>(null)
  const userScrolledRef = useRef(false)
  const lastEventIdRef = useRef<string | null>(null)
  const initialFetchDoneRef = useRef(false)
  const pausedRef = useRef(false)

  const fetchLogs = useCallback(async (incremental = false) => {
    if (pausedRef.current) return
    try {
      setError(null)
      let url = '/data/logixemulator/system/logs?limit=200'
      if (incremental && lastEventIdRef.current) {
        url += `&after=${lastEventIdRef.current}`
      }

      const res = await fetch(url, {
        credentials: 'same-origin',
        signal: AbortSignal.timeout(10000),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()

      if (data.success && data.entries) {
        const newEntries: LogEntry[] = data.entries.map((e: LogEntry) => ({
          id: e.id || '',
          timestamp: e.timestamp,
          level: e.level,
          source: e.source || '',
          logger: e.logger || '',
          message: e.message,
        }))

        if (newEntries.length > 0) {
          const lastEntry = newEntries[newEntries.length - 1]
          if (lastEntry.id) {
            lastEventIdRef.current = lastEntry.id
          }
        }

        if (incremental && initialFetchDoneRef.current) {
          // Append new entries, dedup by id, keep last MAX_ENTRIES
          setEntries(prev => {
            const existingIds = new Set(prev.map(e => e.id).filter(Boolean))
            const unique = newEntries.filter(e => !e.id || !existingIds.has(e.id))
            const combined = [...prev, ...unique]
            return combined.slice(-MAX_ENTRIES)
          })
        } else {
          setEntries(newEntries.slice(-MAX_ENTRIES))
          initialFetchDoneRef.current = true
        }
      } else if (!data.success) {
        setError(data.error || 'Failed to load logs')
      }
    } catch {
      setError('Failed to connect to log endpoint')
    } finally {
      setLoading(false)
    }
  }, [])

  // Keep pausedRef in sync with state
  useEffect(() => {
    pausedRef.current = paused
  }, [paused])

  useEffect(() => {
    fetchLogs(false) // Initial full fetch
    timerRef.current = window.setInterval(() => fetchLogs(true), POLL_INTERVAL)
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

  const togglePause = useCallback(() => {
    setPaused(prev => !prev)
  }, [])

  const clearLogs = useCallback(() => {
    setEntries([])
    lastEventIdRef.current = null
    initialFetchDoneRef.current = false
  }, [])

  const handleRefresh = useCallback(() => {
    lastEventIdRef.current = null
    initialFetchDoneRef.current = false
    setPaused(false)
    setLoading(true)
    fetchLogs(false)
  }, [fetchLogs])

  const applyTextFilter = useCallback(() => {
    setAppliedFilter(textFilter)
  }, [textFilter])

  const clearTextFilter = useCallback(() => {
    setTextFilter('')
    setAppliedFilter('')
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

  const filteredEntries = entries.filter(e => {
    // Level filter
    if (levelFilter !== 'ALL') {
      if (levelFilter === 'ERROR') {
        if (e.level !== 'ERROR' && e.level !== 'FATAL') return false
      } else if (e.level.toUpperCase() !== levelFilter) {
        return false
      }
    }
    // Text filter (client-side, matches source/logger/message)
    if (appliedFilter) {
      const term = appliedFilter.toLowerCase()
      const inSource = (e.source || '').toLowerCase().includes(term)
      const inLogger = (e.logger || '').toLowerCase().includes(term)
      const inMessage = (e.message || '').toLowerCase().includes(term)
      if (!inSource && !inLogger && !inMessage) return false
    }
    return true
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
          <div className="logs-filter-input-wrap">
            <Search size={12} className="logs-filter-icon" />
            <input
              type="text"
              className="logs-filter-input"
              placeholder="Filter logs..."
              value={textFilter}
              onChange={e => setTextFilter(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') applyTextFilter() }}
            />
            {textFilter && (
              <button className="logs-filter-clear" onClick={clearTextFilter} title="Clear filter">
                <X size={12} />
              </button>
            )}
          </div>
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
          <button
            className={`logs-action-btn ${paused ? 'logs-paused' : ''}`}
            onClick={togglePause}
            title={paused ? 'Resume live logs' : 'Pause live logs'}
          >
            {paused ? <Play size={14} /> : <Pause size={14} />}
          </button>
          <button className="logs-action-btn" onClick={exportLogs} title="Export logs">
            <Download size={14} />
          </button>
          <button className="logs-action-btn" onClick={clearLogs} title="Clear logs">
            <Trash2 size={14} />
          </button>
          <button className="logs-action-btn" onClick={handleRefresh} title="Refresh">
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

      {paused && (
        <div className="logs-paused-banner">
          <Pause size={12} />
          <span>Live updates paused</span>
          <button className="logs-resume-inline" onClick={togglePause}>Resume</button>
        </div>
      )}

      <div className="logs-content" ref={contentRef} onScroll={handleScroll}>
        {loading ? (
          <div className="logs-empty">Loading logs...</div>
        ) : filteredEntries.length === 0 ? (
          <div className="logs-empty">
            No log entries
            {appliedFilter ? ` matching "${appliedFilter}"` : ''}
            {levelFilter !== 'ALL' ? ` at level ${levelFilter}` : ''}
          </div>
        ) : (
          filteredEntries.map((entry) => (
            <div key={entry.id || entry.timestamp + entry.message} className={`log-entry ${getLevelClass(entry.level)}`}>
              <span className="logs-col-time">{entry.timestamp}</span>
              <span className={`logs-col-level log-badge ${getLevelClass(entry.level)}`}>{entry.level}</span>
              <span className="logs-col-source" title={entry.logger || entry.source}>{entry.source}</span>
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
