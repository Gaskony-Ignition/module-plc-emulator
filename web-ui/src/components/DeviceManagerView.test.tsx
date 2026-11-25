import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import DeviceManagerView from './DeviceManagerView'
import { mockGateway, defaultRoutes, callsTo, headersOf, jsonResponse } from '../test/gatewayMock'

async function selectDevice(name: string) {
  const select = await screen.findByLabelText('Device')
  await screen.findByRole('option', { name: new RegExp(name) })
  fireEvent.change(select, { target: { value: name } })
}

describe('DeviceManagerView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    sessionStorage.clear()
  })

  it('shows the empty message when no devices are configured', async () => {
    mockGateway({ ...defaultRoutes(), '/devices': { success: true, devices: [], count: 0 } })
    render(<DeviceManagerView />)

    expect(
      await screen.findByText('No devices configured. Add a device through the Ignition Gateway to get started.')
    ).toBeInTheDocument()
  })

  it('shows the selected device status and file versions', async () => {
    mockGateway(defaultRoutes())
    render(<DeviceManagerView />)
    expect(screen.getByRole('heading', { name: 'Device Manager' })).toBeInTheDocument()

    await selectDevice('Line1_PLC')

    expect(await screen.findByText('Rockwell L5K (Allen-Bradley)')).toBeInTheDocument()
    expect(screen.getByText('(240.0 KB)')).toBeInTheDocument()
    expect(await screen.findByText('Line1_20261118_093000.L5K')).toBeInTheDocument()
    expect(screen.getByText('2 / 5 kept')).toBeInTheDocument()
  })

  it('uploads the chosen file as text/plain with the filename and CSRF headers', async () => {
    const fetchMock = mockGateway({
      ...defaultRoutes(),
      '/upload': {
        success: true, filename: 'Line1.L5K', size: 27, device: 'Line1_PLC',
        message: 'File uploaded and applied to device successfully', status: 'Running',
      },
    })
    const { container } = render(<DeviceManagerView />)
    await selectDevice('Line1_PLC')

    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    const file = new File(['CONTROLLER Line1 (Major := 33)'], 'Line1.L5K', { type: 'text/plain' })
    fireEvent.change(input, { target: { files: [file] } })
    fireEvent.click(await screen.findByRole('button', { name: 'Upload' }))

    expect(await screen.findByText('Upload successful')).toBeInTheDocument()
    const [[url, init]] = callsTo(fetchMock, '/upload')
    expect(new URL(String(url), window.location.origin).searchParams.get('device')).toBe('Line1_PLC')
    expect(init?.method).toBe('POST')
    expect(headersOf(init)).toMatchObject({
      'Content-Type': 'text/plain',
      'X-Filename': 'Line1.L5K',
      'X-Requested-With': 'XMLHttpRequest',
    })
    expect(init?.body).toBe('CONTROLLER Line1 (Major := 33)')
  })

  it('Delete Device confirms, then sends DELETE with X-Requested-With', async () => {
    const fetchMock = mockGateway({
      ...defaultRoutes(),
      '/device/Line1_PLC/delete': { success: true, message: 'File deleted successfully', fileName: 'Line1.L5K' },
    })
    render(<DeviceManagerView />)
    await selectDevice('Line1_PLC')

    fireEvent.click(screen.getByRole('button', { name: /Delete Device/ }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Line1_PLC')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const [[, init]] = callsTo(fetchMock, '/device/Line1_PLC/delete')
    expect(init?.method).toBe('DELETE')
    expect(headersOf(init)['X-Requested-With']).toBe('XMLHttpRequest')
  })

  it('shows the gateway error when reverting to an unknown version fails', async () => {
    mockGateway({
      ...defaultRoutes(),
      '/device/Line1_PLC/versions/revert': () =>
        jsonResponse({ success: false, error: 'Unknown version: Line1_20261118_093000.L5K' }, 404),
    })
    render(<DeviceManagerView />)
    await selectDevice('Line1_PLC')

    fireEvent.click(await screen.findByRole('button', { name: /Revert/ }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revert' }))

    expect(await screen.findByText('Unknown version: Line1_20261118_093000.L5K')).toBeInTheDocument()
  })
})
