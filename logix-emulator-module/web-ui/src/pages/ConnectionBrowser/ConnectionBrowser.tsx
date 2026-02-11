import React from "react";
import "./_styles.scss";

/**
 * Connection Browser component
 * Embeds the combined tag browser + file upload interface in an iframe
 */
const ConnectionBrowserPage = () => {
  return (
    <div className="connection-browser-container">
      <iframe
        src="/data/logixemulator/connection-browser"
        className="connection-browser-iframe"
        title="Connection Browser"
        sandbox="allow-same-origin allow-scripts allow-forms allow-downloads"
        referrerPolicy="strict-origin-when-cross-origin"
      />
    </div>
  );
};

export default ConnectionBrowserPage;
