import { describe, it, expect, vi, afterEach } from 'vitest'
import { apiGet, apiPost } from './apiClient'
import { mockGateway, callsTo, headersOf, jsonResponse, devicesPayload } from '../test/gatewayMock'

describe('apiClient', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('apiPost sends JSON with the X-Requested-With header the gateway CSRF check requires', async () => {
    const fetchMock = mockGateway({
      '/device/Line1_PLC/simulation/all': { success: true, enabled: true, simulatedTagCount: 120, skippedReadOnly: 0 },
    })
    const data = await apiPost('/data/logixemulator/device/Line1_PLC/simulation/all', { enabled: true })

    expect(data).toMatchObject({ success: true, simulatedTagCount: 120 })
    const [[, init]] = callsTo(fetchMock, '/simulation/all')
    expect(init?.method).toBe('POST')
    expect(init?.credentials).toBe('same-origin')
    expect(headersOf(init)).toEqual({ 'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest' })
    expect(JSON.parse(String(init?.body))).toEqual({ enabled: true })
  })

  it('apiGet returns the parsed body and rejects on HTTP errors', async () => {
    mockGateway({
      '/devices': devicesPayload,
      '/system/stats': () => jsonResponse({ success: false, error: 'Rate limit exceeded' }, 429),
    })
    await expect(apiGet('/data/logixemulator/devices')).resolves.toEqual(devicesPayload)
    await expect(apiGet('/data/logixemulator/system/stats')).rejects.toThrow('HTTP 429')
  })
})
