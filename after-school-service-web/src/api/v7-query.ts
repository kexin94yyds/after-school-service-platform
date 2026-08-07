export type QueryValue = string | number | boolean | null | undefined

export function compactQuery<T extends object>(
  values: T,
): Record<string, string | number | boolean> {
  return Object.fromEntries(
    Object.entries(values).filter(([, value]) => {
      if (value === null || value === undefined) return false
      return typeof value !== 'string' || value.trim().length > 0
    }),
  ) as Record<string, string | number | boolean>
}
