import type { FormValue, FormValues } from '@/components/entity-crud'

export function formString(values: FormValues, key: string): string {
  const value = values[key]
  return value === null || value === undefined ? '' : String(value).trim()
}

export function formNullableString(
  values: FormValues,
  key: string,
): string | null {
  const value = formString(values, key)
  return value || null
}

export function formNumber(values: FormValues, key: string): number {
  const value = values[key]
  const numberValue =
    typeof value === 'number' ? value : Number(String(value ?? '').trim())
  if (!Number.isFinite(numberValue)) {
    throw new Error(`${key} 必须是有效数字。`)
  }
  return numberValue
}

export function formNumberArray(values: FormValues, key: string): number[] {
  const value = values[key]
  if (!Array.isArray(value)) {
    throw new Error(`${key} 必须是有效列表。`)
  }
  const numbers = value.map(Number)
  if (numbers.some((item) => !Number.isFinite(item))) {
    throw new Error(`${key} 必须是有效列表。`)
  }
  return numbers
}

export function asFormValue(value: unknown): FormValue {
  if (
    typeof value === 'string' ||
    typeof value === 'number' ||
    typeof value === 'boolean' ||
    value === null
  ) {
    return value
  }
  if (
    Array.isArray(value) &&
    value.every((item): item is number => typeof item === 'number')
  ) {
    return value
  }
  return null
}
