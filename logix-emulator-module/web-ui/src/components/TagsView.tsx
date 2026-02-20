import React from 'react'

function TagsView() {
  return (
    <div className="logix-view">
      <iframe
        key="logixemulator-tags-view"
        src="/data/logixemulator/connection-browser"
        className="logix-iframe"
        title="Logix PLC Emulator - Tags"
      />
    </div>
  )
}

export default TagsView
