const BASE = '/data/logixemulator'

export const API = {
  AUTH_CHECK: `${BASE}/auth/check`,
  DEVICES: `${BASE}/devices`,
  DEVICE_STATUS: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/status`,
  DEVICE_TAGS: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/tags`,
  DEVICE_TAGS_CHILDREN: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/tags/children`,
  DEVICE_TAGS_LIVE: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/tags/live`,
  DEVICE_TAGS_SIMULATED: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/tags/simulated`,
  DEVICE_TAG_WRITE: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/tag/write`,
  DEVICE_TAG_SIMULATE: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/tag/simulate`,
  DEVICE_SIMULATION_ALL: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/simulation/all`,
  DEVICE_SIMULATION_SCOPE: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/simulation/scope`,
  DEVICE_DELETE: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/delete`,
  DEVICE_VERSIONS: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/versions`,
  DEVICE_VERSIONS_REVERT: (name: string) => `${BASE}/device/${encodeURIComponent(name)}/versions/revert`,
  UPLOAD: `${BASE}/upload`,
  SYSTEM_STATS: `${BASE}/system/stats`,
  SYSTEM_LOGS: (limit = 100) => `${BASE}/system/logs?limit=${limit}`,
}
