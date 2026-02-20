import React from 'react'
import { FileText } from 'lucide-react'
import './LogsView.css'

function LogsView() {
  return (
    <div className="logs-view">
      <div className="logs-placeholder">
        <FileText size={48} />
        <h3>Logs</h3>
        <p>Coming in a future release</p>
      </div>
    </div>
  )
}

export default LogsView
