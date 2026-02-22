import React, { useState, useEffect, useRef, useCallback } from 'react'
import {
  Tag,
  Search,
  RefreshCw,
  Network,
  List,
  Zap,
  Edit2,
  Clipboard,
} from 'lucide-react'
import { API } from '../constants/api'
import { apiGet, apiPost } from '../utils/apiClient'
import { DeviceInfo } from '../types/device'
import './TagBrowserView.css'

interface TagNode {
  name: string
  path: string
  data_type: string
  value: unknown
  isFolder: boolean
  isUdt?: boolean
  hasChildren?: boolean
  childCount?: number
}

interface SimulatedTagsResponse {
  simulationEngineAvailable: boolean
  simulatedTags: { path: string }[]
}

interface LiveValuesResponse {
  values: Record<string, unknown>
}

interface WriteTagResponse {
  success: boolean
  message?: string
}

interface SimulateTagResponse {
  success: boolean
  message?: string
}

interface Toast {
  id: number
  message: string
  isError: boolean
}

interface WriteModalState {
  tagPath: string
  dataType: string
  currentValue: unknown
}

const PAGE_SIZE = 200


function tagIcon(dataType: string): string {
  const dt = (dataType || '').toUpperCase()
  if (dt === 'BOOL' || dt === 'BOOLEAN') return '🔘'
  if (dt === 'INT' || dt === 'DINT' || dt === 'SINT' || dt === 'LINT') return '🔢'
  if (dt === 'REAL' || dt === 'LREAL' || dt === 'FLOAT') return '📊'
  if (dt === 'STRING') return '📝'
  return '📦'
}

function formatValue(value: unknown, dataType: string): string {
  if (value === null || value === undefined || value === '') return '—'
  const dt = (dataType || '').toUpperCase()
  if (dt === 'BOOL' || dt === 'BOOLEAN') {
    const v = String(value).toLowerCase()
    return v === 'true' || v === '1' ? 'TRUE' : 'FALSE'
  }
  if (dt === 'REAL' || dt === 'LREAL' || dt === 'FLOAT') {
    const n = parseFloat(String(value))
    return isNaN(n) ? String(value) : n.toFixed(2)
  }
  const s = String(value)
  if (s.startsWith('"') && s.endsWith('"')) return s.slice(1, -1)
  return s
}

function valueClass(value: unknown, dataType: string): string {
  const dt = (dataType || '').toUpperCase()
  if (dt === 'BOOL' || dt === 'BOOLEAN') {
    const v = String(value).toLowerCase()
    return v === 'true' || v === '1' ? 'tbv-value-true' : 'tbv-value-false'
  }
  const n = Number(value)
  if (!isNaN(n) && value !== null && value !== '') return 'tbv-value-number'
  if (typeof value === 'string') return 'tbv-value-string'
  return ''
}

function TagBrowserView() {
  const [devices, setDevices] = useState<DeviceInfo[]>([])
  const [selectedDevice, setSelectedDevice] = useState<string>('')
  const [loadingDevices, setLoadingDevices] = useState(false)

  const [rootTags, setRootTags] = useState<TagNode[]>([])
  const [flatTags, setFlatTags] = useState<TagNode[]>([])
  const [flatOffset, setFlatOffset] = useState(0)
  const [flatHasMore, setFlatHasMore] = useState(false)
  const [loadingFlat, setLoadingFlat] = useState(false)

  const [viewMode, setViewMode] = useState<'tree' | 'flat'>('tree')
  const [searchQuery, setSearchQuery] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [loading, setLoading] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  const [liveEnabled, setLiveEnabled] = useState(false)
  const [simulatedPaths, setSimulatedPaths] = useState<Set<string>>(new Set())
  const [simCount, setSimCount] = useState(0)

  const [totalTags, setTotalTags] = useState(0)
  const [folderCount, setFolderCount] = useState(0)
  const [udtCount, setUdtCount] = useState(0)

  const [writeModal, setWriteModal] = useState<WriteModalState | null>(null)
  const [writeValue, setWriteValue] = useState('')
  const [writing, setWriting] = useState(false)

  const [toasts, setToasts] = useState<Toast[]>([])

  // Refs for mutable tree data (avoids stale closure issues with Sets/Maps)
  const loadedChildrenRef = useRef<Record<string, TagNode[]>>({})
  const expandedPathsRef = useRef<Set<string>>(new Set())
  const childrenOffsetRef = useRef<Record<string, number>>({})
  const childrenTotalRef = useRef<Record<string, number>>({})
  const liveValuesRef = useRef<Record<string, string>>({})
  const [renderKey, setRenderKey] = useState(0)
  const forceRerender = useCallback(() => setRenderKey((k) => k + 1), [])

  const liveIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const flatScrollRef = useRef<HTMLDivElement>(null)
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const toastCounterRef = useRef(0)
  const toastTimeoutsRef = useRef<ReturnType<typeof setTimeout>[]>([])

  // ── Toast timeout cleanup on unmount ──────────────────────────────────────

  useEffect(() => {
    return () => {
      toastTimeoutsRef.current.forEach(clearTimeout)
    }
  }, [])

  // ── Toasts ───────────────────────────────────────────────────────────────

  const addToast = useCallback((message: string, isError = false) => {
    const toastId = ++toastCounterRef.current
    setToasts((prev) => [...prev, { id: toastId, message, isError }])
    const id = setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== toastId))
      toastTimeoutsRef.current = toastTimeoutsRef.current.filter((t) => t !== id)
    }, 2500)
    toastTimeoutsRef.current.push(id)
  }, [])

  // ── Load devices ─────────────────────────────────────────────────────────

  useEffect(() => {
    setLoadingDevices(true)
    apiGet<{ devices: DeviceInfo[] }>(API.DEVICES)
      .then((data) => setDevices(data.devices || []))
      .catch(() => setDevices([]))
      .finally(() => setLoadingDevices(false))
  }, [])

  // ── Search debounce ───────────────────────────────────────────────────────

  useEffect(() => {
    if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    searchDebounceRef.current = setTimeout(() => {
      setDebouncedSearch(searchQuery)
    }, 300)
    return () => {
      if (searchDebounceRef.current) clearTimeout(searchDebounceRef.current)
    }
  }, [searchQuery])

  // ── When search changes: switch view mode ─────────────────────────────────

  useEffect(() => {
    if (debouncedSearch.trim()) {
      setViewMode('flat')
    }
  }, [debouncedSearch])

  // ── Load root tags ────────────────────────────────────────────────────────

  const loadRootTags = useCallback(async (device: string) => {
    if (!device) return
    setLoading(true)
    try {
      const url = `${API.DEVICE_TAGS(device)}?limit=${PAGE_SIZE}&depth=1`
      const data = await apiGet<{ tags: TagNode[]; total?: number; folders?: number; udt_instances?: number }>(url)
      setRootTags(data.tags || [])
      setTotalTags(data.total ?? (data.tags || []).length)
      setFolderCount(data.folders ?? (data.tags || []).filter((t) => t.isFolder).length)
      setUdtCount(data.udt_instances ?? (data.tags || []).filter((t) => t.isUdt).length)
      // Reset tree state
      loadedChildrenRef.current = {}
      expandedPathsRef.current = new Set()
      childrenOffsetRef.current = {}
      childrenTotalRef.current = {}
      forceRerender()
    } catch {
      setRootTags([])
    } finally {
      setLoading(false)
    }
  }, [forceRerender])

  // ── Load flat tags ────────────────────────────────────────────────────────

  const loadFlatTags = useCallback(async (device: string, offset = 0, search = '') => {
    if (!device) return
    if (offset === 0) {
      setFlatTags([])
      setFlatOffset(0)
      setFlatHasMore(false)
    }
    setLoadingFlat(true)
    try {
      let url = `${API.DEVICE_TAGS(device)}?flat=true&offset=${offset}&limit=${PAGE_SIZE}`
      if (search.trim()) url += `&search=${encodeURIComponent(search.trim())}`
      const data = await apiGet<{ tags: TagNode[]; total?: number }>(url)
      const newTags = data.tags || []
      if (offset === 0) {
        setFlatTags(newTags)
      } else {
        setFlatTags((prev) => [...prev, ...newTags])
      }
      const total = data.total ?? 0
      const nextOffset = offset + newTags.length
      setFlatOffset(nextOffset)
      setFlatHasMore(nextOffset < total)
    } catch {
      if (offset === 0) setFlatTags([])
    } finally {
      setLoadingFlat(false)
    }
  }, [])

  // ── Load simulated tags ───────────────────────────────────────────────────

  const loadSimulatedTags = useCallback(async (device: string) => {
    if (!device) return
    try {
      const data = await apiGet<SimulatedTagsResponse>(API.DEVICE_TAGS_SIMULATED(device))
      const paths = new Set((data.simulatedTags || []).map((t) => t.path))
      setSimulatedPaths(paths)
      setSimCount(paths.size)
    } catch {
      setSimulatedPaths(new Set())
      setSimCount(0)
    }
  }, [])

  // ── When device changes ───────────────────────────────────────────────────

  useEffect(() => {
    liveValuesRef.current = {}
    if (liveIntervalRef.current) {
      clearInterval(liveIntervalRef.current)
      liveIntervalRef.current = null
    }
    if (!selectedDevice) {
      setRootTags([])
      setFlatTags([])
      setSimulatedPaths(new Set())
      setSimCount(0)
      setTotalTags(0)
      setFolderCount(0)
      setUdtCount(0)
      return
    }
    loadRootTags(selectedDevice)
    loadFlatTags(selectedDevice, 0, '')
    loadSimulatedTags(selectedDevice)
  }, [selectedDevice, loadRootTags, loadFlatTags, loadSimulatedTags])

  // ── Live updates ──────────────────────────────────────────────────────────

  useEffect(() => {
    if (liveIntervalRef.current) {
      clearInterval(liveIntervalRef.current)
      liveIntervalRef.current = null
    }
    if (!liveEnabled || !selectedDevice) return

    const fetchLive = () => {
      apiGet<LiveValuesResponse>(API.DEVICE_TAGS_LIVE(selectedDevice))
        .then((data) => {
          const values = data.values || {}
          const updated: Record<string, string> = {}
          for (const [path, val] of Object.entries(values)) {
            updated[path] = String(val ?? '')
          }
          liveValuesRef.current = { ...liveValuesRef.current, ...updated }
          // Update DOM directly for live cells to avoid full re-render
          for (const [path, val] of Object.entries(updated)) {
            const el = document.querySelector<HTMLElement>(`[data-live-path="${CSS.escape(path)}"]`)
            if (el) el.textContent = val
          }
        })
        .catch(() => { /* ignore */ })
    }

    fetchLive()
    liveIntervalRef.current = setInterval(fetchLive, 1000)
    return () => {
      if (liveIntervalRef.current) clearInterval(liveIntervalRef.current)
    }
  }, [liveEnabled, selectedDevice])

  // ── When debouncedSearch or viewMode changes in flat mode ─────────────────

  useEffect(() => {
    if (viewMode === 'flat' && selectedDevice) {
      loadFlatTags(selectedDevice, 0, debouncedSearch)
    }
  }, [debouncedSearch, viewMode, selectedDevice, loadFlatTags])

  // ── Flat scroll infinite load ─────────────────────────────────────────────

  const handleFlatScroll = useCallback(() => {
    const el = flatScrollRef.current
    if (!el || loadingFlat || !flatHasMore) return
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 100) {
      loadFlatTags(selectedDevice, flatOffset, debouncedSearch)
    }
  }, [flatHasMore, loadingFlat, flatOffset, selectedDevice, debouncedSearch, loadFlatTags])

  // ── Refresh ───────────────────────────────────────────────────────────────

  const handleRefresh = async () => {
    if (!selectedDevice) return
    setRefreshing(true)
    await Promise.all([
      loadRootTags(selectedDevice),
      loadFlatTags(selectedDevice, 0, debouncedSearch),
      loadSimulatedTags(selectedDevice),
    ])
    setRefreshing(false)
  }

  // ── Tree: toggle expand ───────────────────────────────────────────────────

  const toggleExpand = useCallback(async (node: TagNode) => {
    const path = node.path
    if (expandedPathsRef.current.has(path)) {
      expandedPathsRef.current.delete(path)
      forceRerender()
      return
    }
    expandedPathsRef.current.add(path)
    // If children not yet loaded, load them
    if (!loadedChildrenRef.current[path]) {
      try {
        const url = `${API.DEVICE_TAGS_CHILDREN(selectedDevice)}?path=${encodeURIComponent(path)}&offset=0&limit=${PAGE_SIZE}`
        const data = await apiGet<{ tags: TagNode[]; total?: number }>(url)
        loadedChildrenRef.current[path] = data.tags || []
        childrenOffsetRef.current[path] = (data.tags || []).length
        childrenTotalRef.current[path] = data.total ?? (data.tags || []).length
      } catch {
        loadedChildrenRef.current[path] = []
        childrenOffsetRef.current[path] = 0
        childrenTotalRef.current[path] = 0
      }
    }
    forceRerender()
  }, [selectedDevice, forceRerender])

  const loadMoreChildren = useCallback(async (parentPath: string) => {
    const offset = childrenOffsetRef.current[parentPath] ?? 0
    try {
      const url = `${API.DEVICE_TAGS_CHILDREN(selectedDevice)}?path=${encodeURIComponent(parentPath)}&offset=${offset}&limit=${PAGE_SIZE}`
      const data = await apiGet<{ tags: TagNode[]; total?: number }>(url)
      const newItems = data.tags || []
      loadedChildrenRef.current[parentPath] = [
        ...(loadedChildrenRef.current[parentPath] || []),
        ...newItems,
      ]
      childrenOffsetRef.current[parentPath] = offset + newItems.length
      childrenTotalRef.current[parentPath] = data.total ?? (childrenOffsetRef.current[parentPath])
    } catch {
      // ignore
    }
    forceRerender()
  }, [selectedDevice, forceRerender])

  // ── Simulate toggle ───────────────────────────────────────────────────────

  const handleSimToggle = useCallback(async (tagPath: string) => {
    const wasSimulated = simulatedPaths.has(tagPath)
    try {
      await apiPost<SimulateTagResponse>(API.DEVICE_TAG_SIMULATE(selectedDevice), { tagPath })
      await loadSimulatedTags(selectedDevice)
      addToast(wasSimulated ? 'Simulation stopped' : 'Simulation started')
    } catch (err: unknown) {
      addToast(err instanceof Error ? err.message : 'Simulation toggle failed', true)
    }
  }, [selectedDevice, simulatedPaths, loadSimulatedTags, addToast])

  // ── Write modal Escape key ────────────────────────────────────────────────

  useEffect(() => {
    if (!writeModal) return
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setWriteModal(null)
    }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [writeModal])

  // ── Write tag ─────────────────────────────────────────────────────────────

  const openWriteModal = useCallback((tag: TagNode) => {
    setWriteModal({ tagPath: tag.path, dataType: tag.data_type, currentValue: tag.value })
    const cur = formatValue(tag.value, tag.data_type)
    setWriteValue(cur === '—' ? '' : cur)
  }, [])

  const handleWrite = async () => {
    if (!writeModal || !selectedDevice) return
    setWriting(true)
    try {
      await apiPost<WriteTagResponse>(API.DEVICE_TAG_WRITE(selectedDevice), {
        tagPath: writeModal.tagPath,
        value: writeValue,
        dataType: writeModal.dataType,
      })
      addToast('Tag written successfully')
      setWriteModal(null)
      // Refresh tree/flat
      if (viewMode === 'tree') await loadRootTags(selectedDevice)
      else await loadFlatTags(selectedDevice, 0, debouncedSearch)
    } catch (err: unknown) {
      addToast(err instanceof Error ? err.message : 'Write failed', true)
    } finally {
      setWriting(false)
    }
  }

  const copyPath = useCallback((path: string) => {
    navigator.clipboard.writeText(path).then(() => addToast('Path copied')).catch(() => { /* ignore */ })
  }, [addToast])

  // ── Render helpers ────────────────────────────────────────────────────────

  function renderTagRow(tag: TagNode, depth: number) {
    const isSim = simulatedPaths.has(tag.path)
    const liveVal = liveValuesRef.current[tag.path]
    const displayVal = liveVal !== undefined ? liveVal : formatValue(tag.value, tag.data_type)
    const valClass = valueClass(liveVal !== undefined ? liveVal : tag.value, tag.data_type)

    return (
      <div
        key={tag.path}
        className="tbv-row"
        style={{ paddingLeft: depth * 24 + 12 }}
      >
        <div className="tbv-name">
          <span className="tbv-icon">{tagIcon(tag.data_type)}</span>
          <span className="tbv-name-text" title={tag.path}>{tag.name}</span>
          {isSim && <span className="tbv-sim-badge">SIM</span>}
        </div>
        <div className="tbv-type">{tag.data_type || '—'}</div>
        <div className={`tbv-value ${valClass}`} data-live-path={tag.path}>
          {displayVal}
        </div>
        <div className="tbv-actions">
          <button
            className={`tbv-btn ${isSim ? 'tbv-btn-sim-active' : 'tbv-btn-sim'}`}
            title={isSim ? 'Stop simulation' : 'Simulate tag'}
            onClick={() => handleSimToggle(tag.path)}
          >
            <Zap size={11} />
          </button>
          <button
            className="tbv-btn tbv-btn-write"
            title="Write value"
            onClick={() => openWriteModal(tag)}
          >
            <Edit2 size={11} />
          </button>
          <button
            className="tbv-btn tbv-btn-icon"
            title="Copy path"
            onClick={() => copyPath(tag.path)}
          >
            <Clipboard size={11} />
          </button>
        </div>
      </div>
    )
  }

  function renderFolderRow(tag: TagNode, depth: number) {
    const isExpanded = expandedPathsRef.current.has(tag.path)
    const children = loadedChildrenRef.current[tag.path] || []
    const loadedCount = children.length
    const totalCount = childrenTotalRef.current[tag.path] ?? tag.childCount ?? 0
    const hasMore = loadedCount < totalCount

    return (
      <React.Fragment key={tag.path}>
        <div
          className="tbv-row tbv-folder"
          style={{ paddingLeft: depth * 24 + 12 }}
          onClick={() => toggleExpand(tag)}
        >
          <div className="tbv-name">
            <span className={`tbv-expand${isExpanded ? ' expanded' : ''}`}>▶</span>
            <span className="tbv-icon">{tag.isUdt ? '🗂️' : '📁'}</span>
            <span className="tbv-name-text" title={tag.path}>{tag.name}</span>
            {tag.childCount !== undefined && (
              <span className="tbv-child-count">({tag.childCount})</span>
            )}
          </div>
          <div className="tbv-type">{tag.data_type || 'FOLDER'}</div>
          <div className="tbv-value" />
          <div className="tbv-actions" />
        </div>
        {isExpanded && (
          <>
            {children.map((child) => renderNode(child, depth + 1))}
            {hasMore && (
              <div
                className="tbv-load-more"
                style={{ paddingLeft: (depth + 1) * 24 + 12 }}
                onClick={(e) => { e.stopPropagation(); loadMoreChildren(tag.path) }}
              >
                Load more ({loadedCount} / {totalCount})…
              </div>
            )}
          </>
        )}
      </React.Fragment>
    )
  }

  function renderNode(tag: TagNode, depth: number): React.ReactNode {
    if (tag.isFolder || tag.hasChildren) {
      return renderFolderRow(tag, depth)
    }
    return renderTagRow(tag, depth)
  }

  // ── Stats computation ─────────────────────────────────────────────────────

  const currentTags = viewMode === 'flat' ? flatTags : rootTags

  return (
    <div className="tbv-view">
      {/* Header */}
      <div className="tbv-header">
        <div className="tbv-header-left">
          <Tag size={28} color="var(--accent-blue)" />
          <div>
            <h2>
              Tag Browser
              {simCount > 0 && (
                <span className="tbv-sim-count">
                  <Zap size={10} />
                  {simCount} simulating
                </span>
              )}
            </h2>
            <p>Explore and interact with device tags</p>
          </div>
        </div>
        <div className="tbv-header-actions">
          <button
            className="tbv-refresh-btn"
            onClick={handleRefresh}
            disabled={refreshing || loading}
            title="Refresh"
          >
            <RefreshCw size={16} className={refreshing || loading ? 'spinning' : ''} />
          </button>
        </div>
      </div>

      {/* Toolbar card */}
      <div className="tbv-card" style={{ marginBottom: 12 }}>
        <div className="tbv-toolbar">
          {/* Device selector */}
          <div className="tbv-toolbar-group">
            <select
              className="tbv-select"
              value={selectedDevice}
              onChange={(e) => setSelectedDevice(e.target.value)}
              disabled={loadingDevices}
            >
              <option value="">{loadingDevices ? 'Loading…' : '— select device —'}</option>
              {devices.map((d) => (
                <option key={d.name} value={d.name}>{d.name}</option>
              ))}
            </select>
          </div>

          <div className="tbv-divider" />

          {/* Search */}
          <div className="tbv-toolbar-group" style={{ position: 'relative' }}>
            <span className="tbv-search-icon">
              <Search size={13} />
            </span>
            <input
              className="tbv-search"
              type="text"
              placeholder="Search tags…"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>

          <div className="tbv-divider" />

          {/* View toggle */}
          <div className="tbv-toolbar-group">
            <div className="tbv-view-toggle">
              <button
                className={viewMode === 'tree' ? 'active' : ''}
                onClick={() => setViewMode('tree')}
                title="Tree view"
              >
                <Network size={13} />
                Tree
              </button>
              <button
                className={viewMode === 'flat' ? 'active' : ''}
                onClick={() => setViewMode('flat')}
                title="Flat view"
              >
                <List size={13} />
                Flat
              </button>
            </div>
          </div>

          <div className="tbv-divider" />

          {/* Live toggle */}
          <div className="tbv-live-toggle">
            <span className="tbv-live-label">Live</span>
            <button
              className={`tbv-toggle${liveEnabled ? ' active' : ''}`}
              onClick={() => setLiveEnabled((v) => !v)}
              disabled={!selectedDevice}
              title={liveEnabled ? 'Disable live updates' : 'Enable live updates'}
            />
            {liveEnabled && (
              <span className="tbv-live-indicator">
                <span className="tbv-live-dot" />
                LIVE
              </span>
            )}
          </div>
        </div>

        {/* Stats bar */}
        {selectedDevice && !loading && (
          <div className="tbv-stats">
            <span>
              <span className="tbv-stat-label">Tags: </span>
              {totalTags}
            </span>
            <span>
              <span className="tbv-stat-label">Folders: </span>
              {folderCount}
            </span>
            <span>
              <span className="tbv-stat-label">UDT Instances: </span>
              {udtCount}
            </span>
          </div>
        )}
      </div>

      {/* Tag tree/flat card */}
      <div className="tbv-tree-card">
        {/* Column headers */}
        <div className="tbv-tree-header">
          <div className="tbv-th-name">Name</div>
          <div className="tbv-th-type">Type</div>
          <div className="tbv-th-value">Value</div>
          <div className="tbv-th-actions">Actions</div>
        </div>

        {/* Body */}
        {!selectedDevice ? (
          <div className="tbv-empty">
            <div className="tbv-empty-icon">📡</div>
            <div>Select a device to browse tags</div>
            <div className="tbv-empty-sub">Choose a device from the dropdown above</div>
          </div>
        ) : loading ? (
          <div className="tbv-loading">
            <div className="tbv-spinner" />
            Loading tags…
          </div>
        ) : viewMode === 'tree' ? (
          <div className="tbv-tree-body" key={renderKey}>
            {rootTags.length === 0 ? (
              <div className="tbv-empty">
                <div className="tbv-empty-icon">🗂️</div>
                <div>No tags found</div>
                <div className="tbv-empty-sub">Upload an L5X file to populate tags</div>
              </div>
            ) : (
              rootTags.map((tag) => renderNode(tag, 0))
            )}
          </div>
        ) : (
          <div
            className="tbv-tree-body"
            ref={flatScrollRef}
            onScroll={handleFlatScroll}
          >
            {currentTags.length === 0 && !loadingFlat ? (
              <div className="tbv-empty">
                <div className="tbv-empty-icon">🔍</div>
                <div>{debouncedSearch ? 'No tags match your search' : 'No tags found'}</div>
                <div className="tbv-empty-sub">{debouncedSearch ? 'Try a different search term' : 'Upload an L5X file to populate tags'}</div>
              </div>
            ) : (
              <>
                {currentTags.map((tag) =>
                  tag.isFolder || tag.hasChildren
                    ? (
                      <div key={tag.path} className="tbv-row" style={{ paddingLeft: 12 }}>
                        <div className="tbv-name">
                          <span className="tbv-icon">{tag.isUdt ? '🗂️' : '📁'}</span>
                          <span className="tbv-name-text" title={tag.path}>{tag.path}</span>
                        </div>
                        <div className="tbv-type">{tag.data_type || 'FOLDER'}</div>
                        <div className="tbv-value" />
                        <div className="tbv-actions" />
                      </div>
                    )
                    : renderTagRow(tag, 0)
                )}
                {loadingFlat && (
                  <div className="tbv-loading" style={{ padding: '12px 0' }}>
                    <div className="tbv-spinner tbv-spinner-sm" />
                    Loading…
                  </div>
                )}
                {flatHasMore && !loadingFlat && (
                  <div
                    className="tbv-load-more"
                    onClick={() => loadFlatTags(selectedDevice, flatOffset, debouncedSearch)}
                    style={{ textAlign: 'center', padding: '10px' }}
                  >
                    Load more…
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </div>

      {/* Write modal */}
      {writeModal && (
        <div className="tbv-modal-overlay" onClick={() => !writing && setWriteModal(null)}>
          <div className="tbv-write-dialog" onClick={(e) => e.stopPropagation()}>
            <h3>Write Tag Value</h3>
            <div className="tbv-tag-info">
              <span>Path</span>
              <span>{writeModal.tagPath}</span>
              <span>Type</span>
              <span>{writeModal.dataType}</span>
              <span>Current</span>
              <span>{formatValue(writeModal.currentValue, writeModal.dataType)}</span>
            </div>
            <label className="tbv-write-label">New Value</label>
            <input
              className="tbv-write-input"
              type="text"
              value={writeValue}
              onChange={(e) => setWriteValue(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && !writing && handleWrite()}
              autoFocus
            />
            <div className="tbv-write-actions">
              <button
                className="tbv-btn-cancel"
                onClick={() => setWriteModal(null)}
                disabled={writing}
              >
                Cancel
              </button>
              <button
                className="tbv-btn-write"
                onClick={handleWrite}
                disabled={writing}
              >
                {writing ? 'Writing…' : 'Write'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Toasts */}
      <div className="tbv-toasts">
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`tbv-toast${t.isError ? ' tbv-toast-error' : ''}`}
          >
            {t.message}
          </div>
        ))}
      </div>
    </div>
  )
}

export default TagBrowserView
