import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import DiagnosticsView from './DiagnosticsView'
import { mockGateway, defaultRoutes, callsTo, jsonResponse } from '../test/gatewayMock'

describe('DiagnosticsView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows a card per device and the module log', async () => {
    const fetchMock = mockGateway(defaultRoutes())
    render(<DiagnosticsView />)
    expect(screen.getByRole('heading', { name: 'Diagnostics' })).toBeInTheDocument()

    expect(await screen.findByText('Line1.L5K')).toBeInTheDocument()
    expect(screen.getByText('240.0 KB')).toBeInTheDocument()
    expect(screen.getByText('No file')).toBeInTheDocument() // Packaging_PLC hasFile=false
    expect(await screen.findByText('Device Line1_PLC loaded 120 tags')).toBeInTheDocument()

    const [[logUrl]] = callsTo(fetchMock, '/system/logs')
    const params = new URL(String(logUrl), window.location.origin).searchParams
    expect(params.get('limit')).toBe('200')
    expect(params.get('moduleOnly')).toBe('true')
  })

  it('shows the error banner and empty log when the gateway answers 500', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    mockGateway({
      '/devices': () => jsonResponse({ success: false }, 500),
      '/system/logs': () => jsonResponse({ success: false, error: 'Failed to read gateway logs' }, 500),
    })
    render(<DiagnosticsView />)

    expect(await screen.findByText('Failed to load diagnostics')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.getByText('No log entries found')).toBeInTheDocument()
  })

  it('filters the log by level', async () => {
    mockGateway(defaultRoutes())
    render(<DiagnosticsView />)
    const log = screen.getByRole('log', { name: 'Diagnostic log entries' })
    await within(log).findByText('Device Line1_PLC loaded 120 tags')

    fireEvent.click(screen.getByRole('button', { name: 'ERROR' }))

    expect(within(log).getByText('Failed to parse Packaging.json')).toBeInTheDocument()
    expect(within(log).queryByText('Device Line1_PLC loaded 120 tags')).not.toBeInTheDocument()
  })
})
