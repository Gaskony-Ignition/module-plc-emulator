import React from "react";
import "./_styles.scss";

/**
 * PLC File Upload component
 * Embeds the existing HTML-based file upload interface in an iframe
 */
const PLCUploadPage = () => {
  return (
    <div className="plc-upload-container">
      <iframe
        src="/data/plcsimulator/page"
        className="plc-upload-iframe"
        title="PLC File Upload"
      />
    </div>
  );
};

export default PLCUploadPage;
