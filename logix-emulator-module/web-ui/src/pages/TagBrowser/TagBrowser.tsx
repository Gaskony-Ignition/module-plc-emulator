import React from "react";
import "./_styles.scss";

/**
 * Tag Browser component
 * Embeds the existing HTML-based tag browser interface in an iframe
 */
const TagBrowserPage = () => {
  return (
    <div className="tag-browser-container">
      <iframe
        src="/data/logixemulator/tag-browser"
        className="tag-browser-iframe"
        title="PLC Tag Browser"
        sandbox="allow-same-origin allow-scripts allow-forms"
        referrerPolicy="strict-origin-when-cross-origin"
      />
    </div>
  );
};

export default TagBrowserPage;
