import { describe, expect, it } from 'vitest'

import { combineDateAndTime, formatDate, formatPercent } from './format'

describe('report percentage formatting', () => {
  it('renders the backend 0–100 percentage scale without rescaling it', () => {
    expect(formatPercent(0)).toBe('0.0%')
    expect(formatPercent(1)).toBe('1.0%')
    expect(formatPercent(96.4)).toBe('96.4%')
  })
})

describe('date formatting', () => {
  it('formats both LocalDate and offset date-time responses', () => {
    expect(formatDate('2026-09-01')).toBe('2026/09/01')
    expect(formatDate('2026-09-01T00:00:00.000+08:00')).toBe('2026/09/01')
  })

  it('combines API date and time parts even when the date includes an offset', () => {
    const expected = new Date('2026-06-18T15:30:00+08:00').getTime()
    expect(combineDateAndTime('2026-06-18', '15:30:00').getTime()).toBe(expected)
    expect(
      combineDateAndTime(
        '2026-06-18T00:00:00.000+08:00',
        '15:30:00',
      ).getTime(),
    ).toBe(expected)
    expect(Number.isNaN(combineDateAndTime('invalid', '15:30').getTime())).toBe(
      true,
    )
  })
})
