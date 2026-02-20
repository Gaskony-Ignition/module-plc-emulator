import React, { useState, useEffect, useRef } from 'react'
import { HardDrive, Activity } from 'lucide-react'
import './StatusBar.css'

interface DeviceInfo {
  name: string
  simulationEnabled: boolean
}

function StatusBar() {
  const [deviceCount, setDeviceCount] = useState(0)
  const [simulatingCount, setSimulatingCount] = useState(0)
  const timerRef = useRef<number | null>(null)

  useEffect(() => {
    const fetchStats = async () => {
      try {
        const res = await fetch('/data/logixemulator/devices', {
          credentials: 'same-origin',
          signal: AbortSignal.timeout(5000),
        })
        if (!res.ok) return
        const data = await res.json()
        const devices: DeviceInfo[] = data.devices || []
        setDeviceCount(devices.length)
        setSimulatingCount(devices.filter(d => d.simulationEnabled).length)
      } catch {
        // silently ignore fetch errors
      }
    }

    fetchStats()
    timerRef.current = window.setInterval(fetchStats, 15000)
    return () => {
      if (timerRef.current !== null) clearInterval(timerRef.current)
    }
  }, [])

  return (
    <div className="logix-status-bar">
      <div className="logix-status-bar-left">
        <span className="logix-status-bar-item">
          <HardDrive size={11} />
          <span>{deviceCount} device{deviceCount !== 1 ? 's' : ''}</span>
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
