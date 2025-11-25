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
        src="/data/plcsimulator/tag-browser"
        className="tag-browser-iframe"
        title="PLC Tag Browser"
      />
    </div>
  );
};

export default TagBrowserPage;
