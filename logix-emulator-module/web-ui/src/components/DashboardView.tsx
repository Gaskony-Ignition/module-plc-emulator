import React, { useState, useEffect, useCallback, useRef } from 'react'
import {
  LayoutDashboard, HardDrive, Tag, Activity, AlertCircle, ArrowRight
} from 'lucide-react'
import './DashboardView.css'

interface DashboardViewProps {
  onNavigate: (view: string) => void
}

interface DeviceInfo {
  name: string
  status: string
  enabled: boolean
  simulationEnabled: boolean
}

interface DeviceStatus {
  deviceName: string
  status: string
  fileName: string
  parserType: string
  hasFile: boolean
  simulationEnabled: boolean
}

interface DashboardStats {
  deviceCount: number
  totalTags: number
  simulatingDevices: number
  healthyDevices: number
}

function DashboardView({ onNavigate }: DashboardViewProps) {
  const [stats, setStats] = useState<DashboardStats>({
    deviceCount: 0,
    totalTags: 0,
    simulatingDevices: 0,
    healthyDevices: 0,
  })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const refreshTimerRef = useRef<number | null>(null)

  const fetchData = useCallback(async () => {
    try {
      setError(null)
      const res = await fetch('/data/logixemulator/devices', {
        credentials: 'same-origin',
        signal: AbortSignal.timeout(5000),
      })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      const devices: DeviceInfo[] = data.devices || []

      // Fetch status for each device in parallel
      const statusResults = await Promise.allSettled(
        devices.map(d =>
          fetch(`/data/logixemulator/device/${encodeURIComponent(d.name)}/status`, {
            credentials: 'same-origin',
            signal: AbortSignal.timeout(5000),
          }).then(r => r.ok ? r.json() : null)
        )
      )

      let healthyCount = 0
      for (const result of statusResults) {
        if (result.status === 'fulfilled' && result.value) {
          const s: DeviceStatus = result.value
          if (s.status === 'Connected' || s.status === 'Running') healthyCount++
        }
      }

      // Also try to get actual tag counts per device
      const tagResults = await Promise.allSettled(
        devices.map(d =>
          fetch(`/data/logixemulator/device/${encodeURIComponent(d.name)}/tags?limit=1`, {
            credentials: 'same-origin',
            signal: AbortSignal.timeout(5000),
          }).then(r => r.ok ? r.json() : null)
        )
      )

      let tagTotal = 0
      for (const result of tagResults) {
        if (result.status === 'fulfilled' && result.value?.totalTags) {
          tagTotal += result.value.totalTags
        }
      }

      setStats({
        deviceCount: devices.length,
        totalTags: tagTotal,
        simulatingDevices: devices.filter(d => d.simulationEnabled).length,
        healthyDevices: healthyCount,
      })
    } catch (err) {
      setError('Failed to load dashboard data')
      console.error('[DashboardView] Fetch error:', err)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchData()
    refreshTimerRef.current = window.setInterval(fetchData, 15000)
    return () => {
      if (refreshTimerRef.current !== null) clearInterval(refreshTimerRef.current)
    }
  }, [fetchData])

  const statCards = [
    { label: 'Devices', value: stats.deviceCount, icon: HardDrive },
    { label: 'Total Tags', value: stats.totalTags, icon: Tag },
    { label: 'Simulating', value: stats.simulatingDevices, icon: Activity },
    { label: 'Healthy', value: stats.healthyDevices, icon: Activity },
  ]

  return (
    <div className="dashboard-view">
      <div className="dashboard-header">
        <div className="dashboard-header-left">
          <LayoutDashboard size={20} />
          <div>
            <h2>Dashboard</h2>
            <p>Logix PLC Emulator overview</p>
          </div>
        </div>
      </div>

      {error && (
        <div className="dashboard-error">
          <AlertCircle size={16} />
          <span>{error}</span>
          <button className="dashboard-error-retry" onClick={fetchData}>Retry</button>
        </div>
      )}

      <div className="dashboard-stats-grid">
        {statCards.map(card => {
          const Icon = card.icon
          return (
            <div key={card.label} className={`dashboard-stat-card ${loading ? 'loading' : ''}`}>
              <div className="dashboard-stat-card-icon"><Icon size={18} /></div>
              <div className="dashboard-stat-card-label">{card.label}</div>
              <div className="dashboard-stat-card-value">
                {loading ? '\u00A0' : card.value}
              </div>
            </div>
          )
        })}
      </div>

      <div className="dashboard-quick-actions">
        <button className="dashboard-quick-action-btn" onClick={() => onNavigate('devices')}>
          <HardDrive size={16} />
          View Devices
          <ArrowRight size={14} />
        </button>
        <button className="dashboard-quick-action-btn" onClick={() => onNavigate('tags')}>
          <Tag size={16} />
          Browse Tags
          <ArrowRight size={14} />
        </button>
        <button className="dashboard-quick-action-btn" onClick={() => onNavigate('diagnostics')}>
          <Activity size={16} />
          Diagnostics
          <ArrowRight size={14} />
        </button>
      </div>
    </div>
  )
}

export default DashboardView
