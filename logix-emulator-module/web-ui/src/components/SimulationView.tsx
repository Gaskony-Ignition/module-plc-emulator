import React from 'react'
import { Zap } from 'lucide-react'
import './SimulationView.css'

function SimulationView() {
  return (
    <div className="simulation-view">
      <div className="simulation-placeholder">
        <Zap size={48} />
        <h3>Simulation</h3>
        <p>Coming in a future release</p>
        <p className="simulation-placeholder-detail">
          Configure and control tag simulation patterns, bulk enable/disable,
          and monitor simulated tag values in real-time.
        </p>
      </div>
    </div>
  )
}

export default SimulationView
