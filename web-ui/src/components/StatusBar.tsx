import { useState, useEffect, useRef } from 'react'
import { Cpu, MemoryStick, HardDrive, Activity } from 'lucide-react'
import { API } from '../constants/api'
import { apiGet } from '../utils/apiClient'
import { formatBytes } from '../utils/format'
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
        const data = await apiGet<SystemStats & { deviceCount: number }>(API.SYSTEM_STATS)
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
        const data = await apiGet<{ devices: { simulationEnabled: boolean }[] }>(API.DEVICES)
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

  const getUsageColor = (pct: number) => {
    if (pct < 50) return '#a6e3a1'
    if (pct < 75) return '#f9e2af'
    return '#f38ba8'
  }

  const ramPct = stats.ramTotal > 0 ? (stats.ramUsage / stats.ramTotal) * 100 : 0

  return (
    <div className="logix-status-bar">
      <div className="logix-status-bar-left">
        <div className="gsb-metric" title={`CPU: ${stats.cpuUsage.toFixed(1)}%`}>
          <Cpu size={11} />
          <div className="gsb-bar">
            <div
              className="gsb-bar-fill"
              style={{ width: `${Math.min(stats.cpuUsage, 100)}%`, background: getUsageColor(stats.cpuUsage) }}
            />
          </div>
          <span className="gsb-value">{stats.cpuUsage.toFixed(0)}%</span>
        </div>
        <div className="gsb-metric" title={`RAM: ${formatBytes(stats.ramUsage)} / ${formatBytes(stats.ramTotal)}`}>
          <MemoryStick size={11} />
          <div className="gsb-bar">
            <div
              className="gsb-bar-fill"
              style={{ width: `${Math.min(ramPct, 100)}%`, background: getUsageColor(ramPct) }}
            />
          </div>
          <span className="gsb-value">{formatBytes(stats.ramUsage)}</span>
        </div>
      </div>
      <div className="logix-status-bar-right">
        <span className="logix-status-bar-item">
          <HardDrive size={11} />
          <span>{stats.deviceCount} device{stats.deviceCount !== 1 ? 's' : ''}</span>
        </span>
        <span className="logix-status-bar-separator">|</span>
        <span className="logix-status-bar-item">
          <Activity size={11} />
          <span>{simulatingCount} simulating</span>
        </span>
      </div>
    </div>
  )
}

export default StatusBar
