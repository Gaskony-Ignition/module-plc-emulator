import React, { useState, useEffect } from 'react';
import { FileUploadDialog } from './FileUploadDialog';
import './DeviceManager.css';

interface DeviceManagerProps {
  deviceName: string;
}

interface DeviceStatus {
  enabled: boolean;
  tagCount: number;
  lastUpdate: string;
  fileName: string | null;
}

export const DeviceManager: React.FC<DeviceManagerProps> = ({ deviceName }) => {
  const [showUploadDialog, setShowUploadDialog] = useState(false);
  const [status, setStatus] = useState<DeviceStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchDeviceStatus();
  }, [deviceName]);

  const fetchDeviceStatus = async () => {
    try {
      setLoading(true);
      const response = await fetch(`/system/plcsimulator/device/${encodeURIComponent(deviceName)}/status`);
      if (!response.ok) {
        throw new Error('Failed to fetch device status');
      }
      const data = await response.json();
      setStatus(data);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load device status');
    } finally {
      setLoading(false);
    }
  };

  const handleUpload = async (fileName: string, content: string): Promise<void> => {
    const response = await fetch(`/system/plcsimulator/device/${encodeURIComponent(deviceName)}/upload`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        fileName,
        fileContent: content,
      }),
    });

    if (!response.ok) {
      const errorData = await response.json();
      throw new Error(errorData.message || 'Upload failed');
    }

    // Refresh device status after successful upload
    await fetchDeviceStatus();
  };

  if (loading) {
    return (
      <div className="device-manager">
        <div className="loading-spinner">
          <div className="spinner"></div>
          <p>Loading device information...</p>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="device-manager">
        <div className="error-panel">
          <h3>Error Loading Device</h3>
          <p>{error}</p>
          <button onClick={fetchDeviceStatus} className="retry-button">
            Retry
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="device-manager">
      <div className="device-header">
        <div>
          <h2>Device: {deviceName}</h2>
          <p className="device-subtitle">PLC Simulator Program Management</p>
        </div>
        <button
          className="import-button-main"
          onClick={() => setShowUploadDialog(true)}
        >
          <svg className="button-icon" viewBox="0 0 20 20" fill="currentColor">
            <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
          </svg>
          Import Program
        </button>
      </div>

      <div className="device-status-card">
        <h3>Current Program Status</h3>
        <div className="status-grid">
          <div className="status-item">
            <span className="status-label">Status:</span>
            <span className={`status-badge ${status?.enabled ? 'enabled' : 'disabled'}`}>
              {status?.enabled ? 'Enabled' : 'Disabled'}
            </span>
          </div>
          <div className="status-item">
            <span className="status-label">Tag Count:</span>
            <span className="status-value">{status?.tagCount || 0} tags</span>
          </div>
          <div className="status-item">
            <span className="status-label">Last Update:</span>
            <span className="status-value">
              {status?.lastUpdate ? new Date(status.lastUpdate).toLocaleString() : 'Never'}
            </span>
          </div>
          <div className="status-item">
            <span className="status-label">Program File:</span>
            <span className="status-value">
              {status?.fileName || 'No file loaded'}
            </span>
          </div>
        </div>
      </div>

      <div className="info-card">
        <h4>About Program Import</h4>
        <p>
          Import PLC program files to configure the simulator tags. Supported formats include:
        </p>
        <ul>
          <li><strong>Rockwell L5K</strong> - Allen-Bradley RSLogix/Studio 5000 export files</li>
          <li><strong>JSON</strong> - Custom JSON tag definition format</li>
          <li><strong>CSV</strong> - Comma-separated tag list</li>
          <li><strong>Siemens XML</strong> - TIA Portal export files</li>
        </ul>
        <p className="help-text">
          Click "Import Program" to upload a new program file. The simulator will automatically
          parse the file and create OPC-UA tags in a hierarchical structure.
        </p>
      </div>

      {showUploadDialog && (
        <FileUploadDialog
          onUpload={handleUpload}
          onClose={() => setShowUploadDialog(false)}
          acceptedExtensions={['.l5k', '.json', '.csv', '.xml']}
        />
      )}
    </div>
  );
};
