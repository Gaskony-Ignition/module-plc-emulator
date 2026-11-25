import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import App from './App'
import { mockGateway, defaultRoutes, jsonResponse } from './test/gatewayMock'

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('renders the dashboard, sidebar and status bar from gateway data', async () => {
    mockGateway(defaultRoutes())
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Dashboard' })).toBeInTheDocument()
    expect(screen.getByRole('navigation')).toBeInTheDocument()
    expect(screen.getByText('v11.1.5')).toBeInTheDocument()
    // Status bar: device count from /system/stats, simulating count from /devices.
    expect(await screen.findByText('2 devices')).toBeInTheDocument()
    expect(await screen.findByText('1 simulating')).toBeInTheDocument()
  })

  it('shows the login prompt when /auth/check answers 401', async () => {
    mockGateway({ ...defaultRoutes(), '/auth/check': () => jsonResponse({ authenticated: false }, 401) })
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Authentication Required' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Log In to Gateway' })).toHaveAttribute('href', '/web/login')
  })

  it.each([
    ['Devices', 'Device Manager'],
    ['Tags', 'Tag Browser'],
    ['Simulation', 'Simulation Control'],
    ['Diagnostics', 'Diagnostics'],
  ])('navigates to %s from the sidebar', async (navLabel, heading) => {
    mockGateway(defaultRoutes())
    render(<App />)

    const nav = screen.getByRole('navigation')
    fireEvent.click(within(nav).getByRole('button', { name: navLabel }))

    expect(await screen.findByRole('heading', { name: new RegExp(`^${heading}`) })).toBeInTheDocument()
    expect(within(nav).getByRole('button', { name: navLabel })).toHaveAttribute('aria-current', 'page')
  })
})
