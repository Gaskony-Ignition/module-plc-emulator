import React, { useState, useEffect, useRef } from 'react'
import { Cpu, HardDrive, Activity } from 'lucide-react'
import './StatusBar.css'

interface SystemStats {
  cpuUsage: number
  ramUsage: number
  ramTotal: number
  deviceCount: number
}

function StatusBar() {
  const [stats, setStats] = useState<SystemStats>({
    cpuUsage: 0, ramUsage: 0, ramTotal: 0, deviceCount: 0,
  })
  const [simulatingCount, setSimulatingCount] = useState(0)
  const statsTimerRef = useRef<number | null>(null)
  const devicesTimerRef = useRef<number | null>(null)

  // Fetch system stats (CPU/RAM) every 5s
  useEffect(() => {
    const fetchStats = async () => {
      try {
        const res = await fetch('/data/logixemulator/system/stats', {
          credentials: 'same-origin',
          signal: AbortSignal.timeout(5000),
        })
        if (!res.ok) return
        const data = await res.json()
        setStats({
          cpuUsage: data.cpuUsage ?? 0,
          ramUsage: data.ramUsage ?? 0,
          ramTotal: data.ramTotal ?? 0,
          deviceCount: data.deviceCount ?? 0,
        })
      } catch {
        // silently ignore
      }
    }
    fetchStats()
    statsTimerRef.current = window.setInterval(fetchStats, 5000)
    return () => {
      if (statsTimerRef.current !== null) clearInterval(statsTimerRef.current)
    }
  }, [])

  // Fetch device simulation counts every 15s
  useEffect(() => {
    const fetchDevices = async () => {
      try {
        const res = await fetch('/data/logixemulator/devices', {
          credentials: 'same-origin',
          signal: AbortSignal.timeout(5000),
        })
        if (!res.ok) return
        const data = await res.json()
        const devices = data.devices || []
        setSimulatingCount(devices.filter((d: { simulationEnabled: boolean }) => d.simulationEnabled).length)
      } catch {
        // silently ignore
      }
    }
    fetchDevices()
    devicesTimerRef.current = window.setInterval(fetchDevices, 15000)
    return () => {
      if (devicesTimerRef.current !== null) clearInterval(devicesTimerRef.current)
    }
  }, [])

  const formatBytes = (bytes: number) => {
    if (!bytes) return '0 B'
    if (bytes < 1024) return `${bytes} B`
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(0)} MB`
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
  }

  const getUsageColor = (pct: number) => {
    if (pct < 50) return '#a6e3a1'
    if (pct < 75) return '#f9e2af'
    return '#f38ba8'
  }

  const ramPct = stats.ramTotal > 0 ? (stats.ramUsage / stats.ramTotal) * 100 : 0

  return (
    <div className="logix-status-bar">
      <div className="logix-status-bar-left">
        <span className="gsb-metric">
          <Cpu size={11} />
          <span>CPU</span>
          <span className="gsb-bar">
            <span
              className="gsb-bar-fill"
              style={{ width: `${Math.min(stats.cpuUsage, 100)}%`, background: getUsageColor(stats.cpuUsage) }}
            />
          </span>
          <span>{stats.cpuUsage.toFixed(0)}%</span>
        </span>
        <span className="gsb-metric">
          <span>RAM</span>
          <span className="gsb-bar">
            <span
              className="gsb-bar-fill"
              style={{ width: `${Math.min(ramPct, 100)}%`, background: getUsageColor(ramPct) }}
            />
          </span>
          <span>{formatBytes(stats.ramUsage)}</span>
        </span>
        <span className="logix-status-bar-item">
          <HardDrive size={11} />
          <span>{stats.deviceCount} device{stats.deviceCount !== 1 ? 's' : ''}</span>
        </span>
      </div>
      <div className="logix-status-bar-right">
        <span className="logix-status-bar-item">
          <Activity size={11} />
          <span>{simulatingCount} simulating</span>
        </span>
      </div>
    </div>
  )
}

export default StatusBar
