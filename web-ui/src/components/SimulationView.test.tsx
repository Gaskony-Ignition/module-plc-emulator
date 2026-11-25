import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import SimulationView from './SimulationView'
import { mockGateway, defaultRoutes, callsTo, headersOf, jsonResponse } from '../test/gatewayMock'

describe('SimulationView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the empty message when no devices are configured', async () => {
    mockGateway({ ...defaultRoutes(), '/devices': { success: true, devices: [], count: 0 } })
    render(<SimulationView />)

    expect(await screen.findByText('No devices configured')).toBeInTheDocument()
  })

  it('shows the load error when /devices answers 500', async () => {
    mockGateway({ ...defaultRoutes(), '/devices': () => jsonResponse({ success: false }, 500) })
    render(<SimulationView />)

    expect(await screen.findByText('Failed to load devices')).toBeInTheDocument()
  })

  it('lists simulated tags for a device and Enable All POSTs {enabled: true}', async () => {
    const fetchMock = mockGateway({
      ...defaultRoutes(),
      '/device/Line1_PLC/simulation/all': { success: true, enabled: true, simulatedTagCount: 120, skippedReadOnly: 0 },
    })
    render(<SimulationView />)
    expect(screen.getByText('Loading devices...')).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: /^Simulation Control/ })).toBeInTheDocument()

    fireEvent.click(await screen.findByText('Line1_PLC'))
    expect(await screen.findByText('1 tag simulating')).toBeInTheDocument()
    expect(screen.getByText('Tank_Level')).toBeInTheDocument()
    expect(screen.getByText('SINE')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Enable All/ }))
    await waitFor(() => expect(callsTo(fetchMock, '/simulation/all')).toHaveLength(1))
    const [[, init]] = callsTo(fetchMock, '/simulation/all')
    expect(init?.method).toBe('POST')
    expect(headersOf(init)['X-Requested-With']).toBe('XMLHttpRequest')
    expect(JSON.parse(String(init?.body))).toEqual({ enabled: true })
  })

  it('explains that simulation is off for a device without it enabled', async () => {
    mockGateway(defaultRoutes())
    render(<SimulationView />)

    fireEvent.click(await screen.findByText('Packaging_PLC'))
    expect(
      await screen.findByText('Simulation is not enabled for this device. Enable it in the device configuration settings.')
    ).toBeInTheDocument()
  })
})
