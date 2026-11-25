import { describe, it, expect } from 'vitest'
import { formatBytes } from './format'

describe('formatBytes', () => {
  it.each([
    [null, '0 B'],
    [512, '512.0 B'],
    [1536, '1.5 KB'],
    [536870912, '512.0 MB'],
    [2147483648, '2.0 GB'],
  ])('formats %s as %s', (input, expected) => {
    expect(formatBytes(input)).toBe(expected)
  })

  it('stops at GB rather than inventing a larger unit', () => {
    expect(formatBytes(5 * 1024 ** 4)).toBe('5120.0 GB')
  })
})
