import React from 'react'

function DevicesView() {
  return (
    <div className="logix-view">
      <iframe
        key="logixemulator-devices-view"
        src="/data/logixemulator/connection-browser?mode=devices"
        className="logix-iframe"
        title="Logix PLC Emulator - Device Manager"
      />
    </div>
  )
}

export default DevicesView
