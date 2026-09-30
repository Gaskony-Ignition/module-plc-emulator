import React, { useState, useEffect, useRef, useCallback } from 'react'
import {
  HardDrive,
  Upload,
  Trash2,
  RefreshCw,
  ChevronDown,
  ChevronUp,
} from 'lucide-react'
import PageHeader from './PageHeader'
import Modal from './Modal'
import VersionsPanel from './VersionsPanel'
import { API } from '../constants/api'
import { apiGet, apiFetch } from '../utils/apiClient'
import { DeviceInfo, DeviceStatus } from '../types/device'
import { formatBytes } from '../utils/format'
import './DeviceManagerView.css'

interface PendingFile {
  name: string
  content: string
  size: number
}

function DeviceManagerView() {
  const [devices, setDevices] = useState<DeviceInfo[]>([])
  const [selectedDevice, setSelectedDevice] = useState<string>('')
  const [deviceStatus, setDeviceStatus] = useState<DeviceStatus | null>(null)
  const [loadingDevices, setLoadingDevices] = useState(false)
  const [loadingStatus, setLoadingStatus] = useState(false)
  const [uploadPanelOpen, setUploadPanelOpen] = useState(() => {
    const saved = sessionStorage.getItem('dm-upload-panel-open')
    return saved === null ? true : saved === 'true'
  })
  const [pendingFile, setPendingFile] = useState<PendingFile | null>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadSuccess, setUploadSuccess] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [showDeleteModal, setShowDeleteModal] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [versionsRefreshKey, setVersionsRefreshKey] = useState(0)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const simEnabledCount = devices.filter((d) => d.simulationEnabled).length

  const loadDevices = useCallback(async () => {
    setLoadingDevices(true)
    try {
      const data = await apiGet<{ devices: DeviceInfo[] }>(API.DEVICES)
      setDevices(data.devices || [])
    } catch {
      setDevices([])
    } finally {
      setLoadingDevices(false)
    }
  }, [])

  const loadDeviceStatus = useCallback(async (name: string) => {
    if (!name) return
    setLoadingStatus(true)
    try {
      const data = await apiGet<DeviceStatus>(API.DEVICE_STATUS(name))
      setDeviceStatus(data)
    } catch {
      setDeviceStatus(null)
    } finally {
      setLoadingStatus(false)
    }
  }, [])

  useEffect(() => {
    loadDevices()
  }, [loadDevices])

  useEffect(() => {
    if (selectedDevice) {
      loadDeviceStatus(selectedDevice)
    } else {
      setDeviceStatus(null)
    }
  }, [selectedDevice, loadDeviceStatus])

  // Note: Escape key handling is now provided by the Modal a11y primitive.

  const handleRefresh = async () => {
    setRefreshing(true)
    await loadDevices()
    if (selectedDevice) {
      await loadDeviceStatus(selectedDevice)
    }
    setRefreshing(false)
  }

  const toggleUploadPanel = () => {
    const next = !uploadPanelOpen
    setUploadPanelOpen(next)
    sessionStorage.setItem('dm-upload-panel-open', String(next))
  }

  const readFile = (file: File): Promise<PendingFile> => {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = (e) => {
        resolve({
          name: file.name,
          content: e.target?.result as string,
          size: file.size,
        })
      }
      reader.onerror = () => reject(new Error('Failed to read file'))
      reader.readAsText(file)
    })
  }

  const handleFileDrop = async (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setDragOver(false)
    const file = e.dataTransfer.files[0]
    if (!file) return
    try {
      const pf = await readFile(file)
      setPendingFile(pf)
      setUploadSuccess(false)
      setUploadError(null)
    } catch {
      setUploadError('Failed to read file')
    }
  }

  const handleFileInput = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    try {
      const pf = await readFile(file)
      setPendingFile(pf)
      setUploadSuccess(false)
      setUploadError(null)
    } catch {
      setUploadError('Failed to read file')
    }
    e.target.value = ''
  }

  const handleUpload = async () => {
    if (!pendingFile || !selectedDevice) return
    setUploading(true)
    setUploadError(null)
    try {
      const res = await apiFetch(`${API.UPLOAD}?device=${encodeURIComponent(selectedDevice)}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'text/plain',
          'X-Filename': pendingFile.name,
          'X-Requested-With': 'XMLHttpRequest',
        },
        body: pendingFile.content,
      })
      if (!res.ok) {
        const text = await res.text().catch(() => `HTTP ${res.status}`)
        throw new Error(text || `HTTP ${res.status}`)
      }
      setUploadSuccess(true)
      await loadDeviceStatus(selectedDevice)
      await loadDevices()
      setVersionsRefreshKey((k) => k + 1)
    } catch (err: unknown) {
      setUploadError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  const clearUpload = () => {
    setPendingFile(null)
    setUploadSuccess(false)
    setUploadError(null)
  }

  const handleDelete = async () => {
    if (!selectedDevice) return
    setDeleting(true)
    try {
      await apiFetch(API.DEVICE_DELETE(selectedDevice), {
        method: 'DELETE',
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
      })
      setShowDeleteModal(false)
      setSelectedDevice('')
      setDeviceStatus(null)
      clearUpload()
      await loadDevices()
    } catch {
      // ignore — device may still be removed
      setShowDeleteModal(false)
      await loadDevices()
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div className="dm-view">
      {/* Header */}
      <PageHeader icon={HardDrive} title="Device Manager" subtitle={`Upload L5K files and manage PLC device configurations${simEnabledCount > 0 ? ` \u00b7 ${simEnabledCount} simulating` : ''}`}>
        <button className="dm-refresh-btn" onClick={handleRefresh} disabled={refreshing || loadingDevices} title="Refresh">
          <RefreshCw size={16} className={refreshing || loadingDevices ? 'spinning' : ''} />
        </button>
      </PageHeader>

      {/* Toolbar */}
      <div className="dm-card" style={{ marginBottom: 16 }}>
        <div className="dm-toolbar">
          <div className="dm-toolbar-group">
            <label className="dm-label" htmlFor="dm-device-select">Device</label>
            <select
              id="dm-device-select"
              className="dm-select"
              value={selectedDevice}
              onChange={(e) => setSelectedDevice(e.target.value)}
              disabled={loadingDevices}
            >
              <option value="">{loadingDevices ? 'Loading…' : '— select device —'}</option>
              {devices.map((d) => (
                <option key={d.name} value={d.name}>
                  {d.name}
                  {d.simulationEnabled ? ' ⚡' : ''}
                </option>
              ))}
            </select>
          </div>

          {selectedDevice && (
            <>
              <div className="dm-toolbar-divider" />
              <div className="dm-toolbar-group">
                <button
                  className="dm-collapse-toggle"
                  onClick={toggleUploadPanel}
                >
                  {uploadPanelOpen ? (
                    <>
                      <ChevronUp size={13} />
                      Hide Upload
                    </>
                  ) : (
                    <>
                      <ChevronDown size={13} />
                      Show Upload
                    </>
                  )}
                </button>
              </div>
              <div className="dm-toolbar-divider" />
              <div className="dm-toolbar-group">
                <button
                  className="dm-btn dm-btn-danger"
                  onClick={() => setShowDeleteModal(true)}
                >
                  <Trash2 size={13} />
                  Delete Device
                </button>
              </div>
            </>
          )}
        </div>

        {/* Device panel */}
        {selectedDevice && (
          <div className="dm-device-panel">
            {/* Device info */}
            <div className="dm-device-info">
              {loadingStatus ? (
                <div className="dm-no-file">Loading device info…</div>
              ) : deviceStatus ? (
                <>
                  <div className="dm-file-row">
                    <span className="dm-file-label">Device</span>
                    <span className="dm-file-status">{deviceStatus.deviceName}</span>
                  </div>
                  <div className="dm-file-row">
                    <span className="dm-file-label">Status</span>
                    <span
                      className="dm-file-status"
                      style={{
                        color:
                          deviceStatus.status === 'Connected'
                            ? 'var(--accent-secondary)'
                            : deviceStatus.status === 'Faulted'
                              ? 'var(--error)'
                              : 'var(--accent-warning)',
                      }}
                    >
                      {deviceStatus.status}
                    </span>
                  </div>
                  <div className="dm-file-row">
                    <span className="dm-file-label">Parser</span>
                    <span className="dm-file-status">{deviceStatus.parserType || '—'}</span>
                  </div>
                  <div className="dm-file-row">
                    <span className="dm-file-label">Simulation</span>
                    <span
                      className="dm-file-status"
                      style={{
                        color: deviceStatus.simulationEnabled
                          ? 'var(--accent-warning)'
                          : 'var(--text-muted)',
                      }}
                    >
                      {deviceStatus.simulationEnabled ? 'Enabled' : 'Disabled'}
                    </span>
                  </div>
                  <div className="dm-file-row" style={{ marginTop: 6, paddingTop: 6, borderTop: '1px solid var(--border-default)' }}>
                    <span className="dm-file-label">Current File</span>
                    {deviceStatus.hasFile ? (
                      <span className="dm-file-status">
                        {deviceStatus.fileName}
                        <span style={{ color: 'var(--text-muted)', marginLeft: 8, fontSize: 11 }}>
                          ({formatBytes(deviceStatus.fileSize)})
                        </span>
                      </span>
                    ) : (
                      <span className="dm-no-file">No file loaded</span>
                    )}
                  </div>
                  {deviceStatus.lastModified > 0 && (
                    <div className="dm-file-row">
                      <span className="dm-file-label">Modified</span>
                      <span className="dm-file-status" style={{ fontSize: 11 }}>
                        {new Date(deviceStatus.lastModified).toLocaleString()}
                      </span>
                    </div>
                  )}
                </>
              ) : (
                <div className="dm-no-file">No device info available</div>
              )}
            </div>

            {/* Upload panel */}
            {uploadPanelOpen && (
              <div className="dm-upload-panel">
                <div
                  className={`dm-upload-zone${dragOver ? ' drag-over' : ''}`}
                  onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
                  onDragLeave={() => setDragOver(false)}
                  onDrop={handleFileDrop}
                  onClick={() => fileInputRef.current?.click()}
                  role="button"
                  tabIndex={0}
                  onKeyDown={(e) => e.key === 'Enter' && fileInputRef.current?.click()}
                >
                  <Upload size={20} color="var(--text-muted)" />
                  <span className="dm-upload-zone-text">
                    {pendingFile ? pendingFile.name : 'Drop L5K file or click'}
                  </span>
                  {pendingFile && (
                    <span className="dm-upload-zone-meta">{formatBytes(pendingFile.size)}</span>
                  )}
                </div>
                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".l5k,.L5K,.json,.JSON,.csv,.CSV"
                  style={{ display: 'none' }}
                  onChange={handleFileInput}
                />

                {pendingFile && !uploadSuccess && (
                  <div className="dm-upload-result">
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="dm-upload-filename">{pendingFile.name}</div>
                      <div className="dm-upload-meta">{formatBytes(pendingFile.size)}</div>
                      {uploadError && (
                        <div className="dm-upload-error">{uploadError}</div>
                      )}
                    </div>
                    <div className="dm-upload-actions">
                      <button
                        className="dm-btn dm-btn-secondary"
                        onClick={clearUpload}
                        disabled={uploading}
                      >
                        Clear
                      </button>
                      <button
                        className="dm-btn dm-btn-success"
                        onClick={handleUpload}
                        disabled={uploading}
                      >
                        {uploading ? 'Uploading…' : 'Upload'}
                      </button>
                    </div>
                  </div>
                )}

                {uploadSuccess && (
                  <div className="dm-upload-result dm-upload-done">
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="dm-upload-success">Upload successful</div>
                      <div className="dm-upload-filename">{pendingFile?.name}</div>
                    </div>
                    <div className="dm-upload-actions">
                      <button className="dm-btn dm-btn-secondary" onClick={clearUpload}>
                        Clear
                      </button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {!selectedDevice && !loadingDevices && devices.length === 0 && (
          <div className="dm-no-file" style={{ marginTop: 12, textAlign: 'center' }}>
            No devices configured. Add a device through the Ignition Gateway to get started.
          </div>
        )}
      </div>

      {/* File version history + revert (defect B5) */}
      {selectedDevice && (
        <VersionsPanel
          deviceName={selectedDevice}
          refreshSignal={versionsRefreshKey}
          onReverted={handleRefresh}
        />
      )}

      {/* Delete confirmation modal (a11y primitive) */}
      <Modal
        isOpen={showDeleteModal}
        onClose={() => { if (!deleting) setShowDeleteModal(false) }}
        closeOnBackdrop={!deleting}
        title="Delete Device?"
        backdropClassName="dm-modal-overlay"
        className="dm-modal-dialog"
        showCloseButton={false}
        footer={
          <div className="dm-modal-actions">
            <button
              className="dm-btn dm-btn-secondary"
              onClick={() => setShowDeleteModal(false)}
              disabled={deleting}
            >
              Cancel
            </button>
            <button
              className="dm-btn dm-btn-danger"
              onClick={handleDelete}
              disabled={deleting}
            >
              {deleting ? 'Deleting…' : 'Delete'}
            </button>
          </div>
        }
      >
        <p>
          Are you sure you want to delete <strong>{selectedDevice}</strong>?
          This will remove the device and its associated PLC file.
        </p>
      </Modal>
    </div>
  )
}

export default DeviceManagerView
