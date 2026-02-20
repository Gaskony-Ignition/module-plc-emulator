import React from 'react'

function DevicesView() {
  return (
    <div className="logix-view">
      <iframe
        key="logixemulator-devices-view"
        src="/data/logixemulator/connection-browser"
        className="logix-iframe"
        title="Logix PLC Emulator - Devices"
      />
    </div>
  )
}

export default DevicesView
