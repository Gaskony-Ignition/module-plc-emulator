import React from 'react'

function TagsView() {
  return (
    <div className="logix-view">
      <iframe
        key="logixemulator-tags-view"
        src="/data/logixemulator/connection-browser?mode=tags"
        className="logix-iframe"
        title="Logix PLC Emulator - Tag Browser"
      />
    </div>
  )
}

export default TagsView
