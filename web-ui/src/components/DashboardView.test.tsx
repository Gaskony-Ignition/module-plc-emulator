import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import DashboardView from './DashboardView'
import { mockGateway, defaultRoutes, jsonResponse, callsTo } from '../test/gatewayMock'

function statValue(label: string) {
  return screen.getByText(label, { selector: '.dashboard-stat-card-label' }).nextElementSibling?.textContent
}

describe('DashboardView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('summarises devices, tags, simulation and health', async () => {
    mockGateway(defaultRoutes())
    render(<DashboardView onNavigate={() => {}} />)

    await waitFor(() => expect(statValue('Devices')).toBe('2'))
    expect(statValue('Total Tags')).toBe('120') // Packaging_PLC has none
    expect(statValue('Simulating')).toBe('1')
    expect(statValue('Healthy')).toBe('1') // Line1 Running, Packaging Faulted
  })

  it('shows the error banner when /devices fails, and Retry refetches', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    let fail = true
    const fetchMock = mockGateway({
      ...defaultRoutes(),
      '/devices': () => (fail ? jsonResponse({ success: false, error: 'boom' }, 500) : jsonResponse(defaultRoutes()['/devices'])),
    })
    render(<DashboardView onNavigate={() => {}} />)

    expect(await screen.findByText('Failed to load dashboard data')).toBeInTheDocument()
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(screen.queryByText('Failed to load dashboard data')).not.toBeInTheDocument())
    expect(callsTo(fetchMock, '/devices')).toHaveLength(2)
    expect(statValue('Devices')).toBe('2')
  })

  it('quick actions navigate to the matching view', () => {
    mockGateway(defaultRoutes())
    const onNavigate = vi.fn()
    render(<DashboardView onNavigate={onNavigate} />)

    fireEvent.click(screen.getByRole('button', { name: /Browse Tags/ }))
    expect(onNavigate).toHaveBeenCalledWith('tags')
  })
})
