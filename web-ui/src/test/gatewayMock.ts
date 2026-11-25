/**
 * Test helpers: a routed `fetch` stand-in for the Logix PLC Emulator gateway.
 *
 * Fixture payloads mirror what the gateway controllers actually return
 * (DeviceController, TagController/TagTreeBuilder, SimulationController,
 * SystemController, VersionController) so the UI is exercised against real
 * response shapes rather than invented ones.
 */
import { vi } from 'vitest'

export const BASE = '/data/logixemulator'

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

type Handler = (url: URL, init?: RequestInit) => Response | Promise<Response>
type RouteValue = unknown | Handler

/**
 * Installs a mocked global fetch. Keys are paths without the module prefix
 * (e.g. `/devices`). A key matches its exact path; a key ending in `/` matches
 * as a prefix. Unmatched paths answer 404 so a missing mock is obvious.
 */
export function mockGateway(routes: Record<string, RouteValue>) {
  const prefixKeys = Object.keys(routes).filter(k => k.endsWith('/')).sort((a, b) => b.length - a.length)
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), window.location.origin)
    const path = url.pathname.startsWith(BASE) ? url.pathname.slice(BASE.length) : url.pathname
    const key = path in routes ? path : prefixKeys.find(k => path.startsWith(k))
    if (key === undefined) {
      return jsonResponse({ success: false, error: `Not mocked: ${path}` }, 404)
    }
    const value = routes[key]
    if (typeof value === 'function') return (value as Handler)(url, init)
    return jsonResponse(value)
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

/** The recorded fetch calls whose URL path (minus query) ends with `pathSuffix`. */
export function callsTo(fn: ReturnType<typeof mockGateway>, pathSuffix: string) {
  return fn.mock.calls.filter(([input]) =>
    new URL(String(input), window.location.origin).pathname.endsWith(pathSuffix)
  )
}

export function headersOf(init: RequestInit | undefined): Record<string, string> {
  return (init?.headers ?? {}) as Record<string, string>
}

// ---- Fixtures (shapes from the gateway Java controllers) ----

/** DeviceController.handleListDevices */
export const devicesPayload = {
  success: true,
  devices: [
    {
      name: 'Line1_PLC',
      status: 'Running',
      enabled: true,
      fileName: 'Line1.L5K',
      parserType: 'Rockwell L5K (Allen-Bradley)',
      simulationEnabled: true,
      simulating: true,
    },
    {
      name: 'Packaging_PLC',
      status: 'Faulted',
      enabled: true,
      fileName: 'Packaging.json',
      parserType: 'JSON Format',
      simulationEnabled: false,
      simulating: false,
    },
  ],
  count: 2,
}

/** DeviceController.handleDeviceStatus */
export function deviceStatusPayload(name: string) {
  const running = name === 'Line1_PLC'
  return {
    success: true,
    deviceName: name,
    status: running ? 'Running' : 'Faulted',
    fileName: running ? 'Line1.L5K' : 'Packaging.json',
    hasFile: running,
    fileSize: running ? 245760 : 0,
    lastModified: running ? 1763634900000 : 0,
    filePath: running ? 'Line1.L5K' : null,
    parserType: running ? 'Rockwell L5K (Allen-Bradley)' : 'JSON Format',
    enabled: true,
    simulationEnabled: running,
    simulating: running,
  }
}

/** TagController.handleGetTags (tree mode, depth=1) */
export const rootTagsPayload = {
  success: true,
  deviceName: 'Line1_PLC',
  path: '',
  tags: [
    {
      name: 'Program:MainProgram',
      path: 'Program:MainProgram',
      data_type: 'Program',
      isFolder: true,
      isUdt: false,
      childCount: 12,
      hasChildren: true,
    },
    {
      name: 'Tank_Level',
      path: 'Controller:Global/Tank_Level',
      data_type: 'REAL',
      value: 42.517,
      isFolder: false,
    },
    {
      name: 'Pump_Run',
      path: 'Controller:Global/Pump_Run',
      data_type: 'BOOL',
      value: 'true',
      isFolder: false,
    },
  ],
  offset: 0,
  limit: 200,
  count: 3,
  totalAtLevel: 3,
  total: 120,
  hasMore: false,
  totalTags: 120,
  folders: 8,
  udtInstances: 4,
  udt_instances: 4,
}

/** SimulationController.handleGetSimulatedTags */
export const simulatedTagsPayload = {
  success: true,
  deviceName: 'Line1_PLC',
  simulationEngineAvailable: true,
  simulatedTags: [{ path: 'Controller:Global/Tank_Level', pattern: 'SINE' }],
  count: 1,
}

/** SystemController.handleSystemStats */
export const systemStatsPayload = {
  success: true,
  ramUsage: 536870912,
  ramTotal: 2147483648,
  cpuUsage: 12.5,
  moduleVersion: '11.1.5',
  deviceCount: 2,
}

/** SystemController.handleSystemLogs */
export const systemLogsPayload = {
  success: true,
  entries: [
    {
      id: '5001',
      timestamp: '2026-11-25 08:00:00',
      epochMs: 1764057600000,
      level: 'INFO',
      source: 'LogixEmulatorDevice',
      logger: 'com.inductiveautomation.logixemulator.gateway.device.LogixEmulatorDevice',
      message: 'Device Line1_PLC loaded 120 tags',
    },
    {
      id: '5002',
      timestamp: '2026-11-25 08:00:02',
      epochMs: 1764057602000,
      level: 'ERROR',
      source: 'JsonPLCParser',
      logger: 'com.inductiveautomation.logixemulator.gateway.parser.JsonPLCParser',
      message: 'Failed to parse Packaging.json',
    },
  ],
  count: 2,
  lastEventId: 5002,
  hasMore: false,
}

/** VersionController.handleListVersions */
export const versionsPayload = {
  success: true,
  deviceName: 'Line1_PLC',
  versions: [
    { filename: 'Line1.L5K', size: 245760, timestamp: 1763634900000, current: true },
    { filename: 'Line1_20261118_093000.L5K', size: 240128, timestamp: 1763458200000, current: false },
  ],
  count: 2,
  maxVersions: 5,
}

/** The read-only routes the views hit on load. */
export function defaultRoutes(): Record<string, RouteValue> {
  return {
    '/auth/check': { authenticated: true },
    '/devices': devicesPayload,
    '/device/Line1_PLC/status': deviceStatusPayload('Line1_PLC'),
    '/device/Packaging_PLC/status': deviceStatusPayload('Packaging_PLC'),
    '/device/Line1_PLC/tags': rootTagsPayload,
    '/device/Packaging_PLC/tags': { success: true, deviceName: 'Packaging_PLC', tags: [], totalTags: 0, folders: 0, udtInstances: 0 },
    '/device/Line1_PLC/tags/simulated': simulatedTagsPayload,
    '/device/Line1_PLC/versions': versionsPayload,
    '/system/stats': systemStatsPayload,
    '/system/logs': systemLogsPayload,
  }
}
