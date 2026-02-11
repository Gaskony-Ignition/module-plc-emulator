import React from "react";
import "./_styles.scss";

/**
 * Connection Browser component
 * Embeds the combined tag browser + file upload interface in an iframe.
 *
 * The key prop on the iframe is critical — without it, when multiple modules
 * register structurally identical iframe-based pages, React's reconciler
 * reuses the iframe DOM element instead of recreating it, causing the page
 * to appear "stuck" when navigating between modules.
 */
const ConnectionBrowserPage = () => {
  return (
    <div className="connection-browser-container">
      <iframe
        key="logixemulator-connection-browser"
        src="/data/logixemulator/connection-browser"
        className="connection-browser-iframe"
        title="Logix PLC Emulator Connection Browser"
        sandbox="allow-same-origin allow-scripts allow-forms allow-downloads"
        referrerPolicy="strict-origin-when-cross-origin"
      />
    </div>
  );
};

export default ConnectionBrowserPage;
