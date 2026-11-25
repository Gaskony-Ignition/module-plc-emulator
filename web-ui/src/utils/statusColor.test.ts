import { describe, it, expect } from 'vitest'
import { getStatusColor } from './statusColor'

describe('getStatusColor', () => {
  it.each([
    ['Running', 'var(--success)'],
    ['Connected', 'var(--success)'],
    ['Disconnected', 'var(--error)'],
    ['Faulted', 'var(--error)'],
    ['Error: parse failed', 'var(--error)'],
    ['Loading', 'var(--warning)'],
    ['Stopped', 'var(--text-muted)'],
  ])('maps %s to %s', (status, expected) => {
    expect(getStatusColor(status)).toBe(expected)
  })
})
