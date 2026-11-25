import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import TagBrowserView from './TagBrowserView'
import { mockGateway, defaultRoutes, callsTo, headersOf, jsonResponse } from '../test/gatewayMock'

async function selectDevice(name: string) {
  await screen.findByRole('option', { name })
  fireEvent.change(screen.getByLabelText('Device'), { target: { value: name } })
}

function rowOf(tagName: string) {
  return screen.getByText(tagName, { selector: '.tbv-name-text' }).closest('.tbv-row') as HTMLElement
}

describe('TagBrowserView', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('prompts for a device, then shows its tag tree, stats and formatted values', async () => {
    mockGateway(defaultRoutes())
    render(<TagBrowserView />)
    expect(screen.getByRole('heading', { name: /^Tag Browser/ })).toBeInTheDocument()
    expect(screen.getByText('Select a device to browse tags')).toBeInTheDocument()

    await selectDevice('Line1_PLC')

    expect(await screen.findByText('Program:MainProgram')).toBeInTheDocument()
    expect(within(rowOf('Tank_Level')).getByText('42.52')).toBeInTheDocument() // REAL → 2dp
    expect(within(rowOf('Pump_Run')).getByText('TRUE')).toBeInTheDocument() // BOOL
    expect(within(rowOf('Tank_Level')).getByText('SIM')).toBeInTheDocument()
    expect(screen.getByText('Tags:').parentElement).toHaveTextContent('Tags: 120')
    expect(screen.getByText('UDT Instances:').parentElement).toHaveTextContent('UDT Instances: 4')
  })

  it('shows the empty state for a device with no tags', async () => {
    mockGateway(defaultRoutes())
    render(<TagBrowserView />)
    await selectDevice('Packaging_PLC')

    expect(await screen.findByText('Upload an L5K file to populate tags')).toBeInTheDocument()
  })

  it('writes a tag value via POST /tag/write with the CSRF header', async () => {
    const fetchMock = mockGateway({
      ...defaultRoutes(),
      '/device/Line1_PLC/tag/write': {
        success: true, message: 'Tag value written successfully',
        tagPath: 'Controller:Global/Tank_Level', value: '55.5',
      },
    })
    render(<TagBrowserView />)
    await selectDevice('Line1_PLC')
    await screen.findByText('Tank_Level')

    fireEvent.click(within(rowOf('Tank_Level')).getByTitle('Write value'))
    const input = within(screen.getByRole('dialog')).getByLabelText('New Value')
    expect(input).toHaveValue('42.52')
    fireEvent.change(input, { target: { value: '55.5' } })
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Write' }))

    expect(await screen.findByText('Tag written successfully')).toBeInTheDocument()
    const [[, init]] = callsTo(fetchMock, '/tag/write')
    expect(init?.method).toBe('POST')
    expect(headersOf(init)['X-Requested-With']).toBe('XMLHttpRequest')
    expect(JSON.parse(String(init?.body))).toEqual({
      tagPath: 'Controller:Global/Tank_Level', value: '55.5', dataType: 'REAL',
    })
  })

  it('keeps the write dialog open and reports the failure for a read-only tag', async () => {
    mockGateway({
      ...defaultRoutes(),
      '/device/Line1_PLC/tag/write': () => jsonResponse({
        success: false, error: "Tag 'Controller:Global/Pump_Run' is read-only and cannot be written",
      }, 403),
    })
    render(<TagBrowserView />)
    await selectDevice('Line1_PLC')
    await screen.findByText('Pump_Run')

    fireEvent.click(within(rowOf('Pump_Run')).getByTitle('Write value'))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Write' }))

    expect(await screen.findByText('HTTP 403')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument())
  })
})
