import { useState, useEffect, useCallback } from 'react'
import { History, RotateCcw } from 'lucide-react'
import Modal from './Modal'
import { API } from '../constants/api'
import { apiGet, apiFetch } from '../utils/apiClient'
import { FileVersionInfo, VersionsResponse } from '../types/device'
import { formatBytes } from '../utils/format'
import './VersionsPanel.css'

interface VersionsPanelProps {
  deviceName: string
  /** Called after a successful revert so the parent can refresh device status/list. */
  onReverted: () => void | Promise<void>
  /** Bump this (e.g. after an upload) to force a re-fetch of the version list. */
  refreshSignal?: number
}

/**
 * File version history for a device (defect B5 — the version manager previously had no
 * REST route or UI, so uploads never showed a history and revert was unreachable dead code).
 */
function VersionsPanel({ deviceName, onReverted, refreshSignal }: VersionsPanelProps) {
  const [versions, setVersions] = useState<FileVersionInfo[]>([])
  const [maxVersions, setMaxVersions] = useState(5)
  const [loading, setLoading] = useState(false)
  const [revertTarget, setRevertTarget] = useState<FileVersionInfo | null>(null)
  const [reverting, setReverting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadVersions = useCallback(async () => {
    if (!deviceName) return
    setLoading(true)
    try {
      const data = await apiGet<VersionsResponse>(API.DEVICE_VERSIONS(deviceName))
      setVersions(data.versions || [])
      setMaxVersions(data.maxVersions ?? 5)
    } catch {
      setVersions([])
    } finally {
      setLoading(false)
    }
  }, [deviceName])

  useEffect(() => {
    loadVersions()
    // refreshSignal is an intentional extra trigger (e.g. after an upload elsewhere in the
    // Device Manager view) — it has no meaning of its own beyond "re-fetch now".
  }, [loadVersions, refreshSignal])

  const handleRevert = async () => {
    if (!revertTarget) return
    setReverting(true)
    setError(null)
    try {
      const res = await apiFetch(API.DEVICE_VERSIONS_REVERT(deviceName), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
        body: JSON.stringify({ filename: revertTarget.filename }),
      })
      if (!res.ok) {
        const errBody = await res.json().catch(() => null)
        throw new Error(errBody?.error || `HTTP ${res.status}`)
      }
      setRevertTarget(null)
      await loadVersions()
      await onReverted()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Revert failed')
    } finally {
      setReverting(false)
    }
  }

  if (!deviceName) return null

  return (
    <div className="dm-card dm-versions-card">
      <div className="dm-versions-header">
        <History size={15} />
        <span>File Versions</span>
        <span className="dm-versions-count">{versions.length} / {maxVersions} kept</span>
      </div>

      {error && <div className="dm-upload-error">{error}</div>}

      {loading ? (
        <div className="dm-no-file">Loading versions…</div>
      ) : versions.length === 0 ? (
        <div className="dm-no-file">No file versions recorded yet — upload a file to start building history.</div>
      ) : (
        <ul className="dm-versions-list">
          {versions.map((v) => (
            <li key={v.filename} className="dm-versions-row">
              <span className="dm-versions-filename" title={v.filename}>{v.filename}</span>
              <span className="dm-versions-meta">{formatBytes(v.size)}</span>
              <span className="dm-versions-meta">{new Date(v.timestamp).toLocaleString()}</span>
              {v.current ? (
                <span className="dm-versions-current-badge">Current</span>
              ) : (
                <button
                  type="button"
                  className="dm-btn dm-btn-secondary dm-versions-revert-btn"
                  onClick={() => setRevertTarget(v)}
                  disabled={reverting}
                >
                  <RotateCcw size={12} />
                  Revert
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

      <Modal
        isOpen={revertTarget !== null}
        onClose={() => { if (!reverting) setRevertTarget(null) }}
        closeOnBackdrop={!reverting}
        title="Revert to This Version?"
        backdropClassName="dm-modal-overlay"
        className="dm-modal-dialog"
        showCloseButton={false}
        footer={
          <div className="dm-modal-actions">
            <button
              className="dm-btn dm-btn-secondary"
              onClick={() => setRevertTarget(null)}
              disabled={reverting}
            >
              Cancel
            </button>
            <button className="dm-btn dm-btn-danger" onClick={handleRevert} disabled={reverting}>
              {reverting ? 'Reverting…' : 'Revert'}
            </button>
          </div>
        }
      >
        <p>
          Restore <strong>{revertTarget?.filename}</strong> as the current file for{' '}
          <strong>{deviceName}</strong>? The device will reload immediately.
        </p>
      </Modal>
    </div>
  )
}

export default VersionsPanel
