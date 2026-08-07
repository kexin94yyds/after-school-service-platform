import { describe, expect, it } from 'vitest'

import type { FormValues } from '@/components/entity-crud'

import {
  asFormValue,
  formNullableString,
  formNumber,
  formNumberArray,
  formString,
} from './forms'

describe('form value conversion', () => {
  const values: FormValues = {
    name: '  机器人社团  ',
    empty: '   ',
    capacity: '24',
    invalidNumber: '二十四',
    enabled: true,
    studentIds: [3, 8],
  }

  it('normalizes text and nullable text', () => {
    expect(formString(values, 'name')).toBe('机器人社团')
    expect(formNullableString(values, 'empty')).toBeNull()
  })

  it('converts finite numbers and rejects invalid values', () => {
    expect(formNumber(values, 'capacity')).toBe(24)
    expect(() => formNumber(values, 'invalidNumber')).toThrow(
      'invalidNumber 必须是有效数字。',
    )
  })

  it('only exposes supported primitive form values', () => {
    expect(asFormValue(values.enabled)).toBe(true)
    expect(formNumberArray(values, 'studentIds')).toEqual([3, 8])
    expect(asFormValue([3, 8])).toEqual([3, 8])
    expect(asFormValue({ nested: true })).toBeNull()
  })
})
