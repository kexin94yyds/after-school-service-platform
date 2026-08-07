import type { ApiEntity } from '@/api/types'

export type FormValue = string | number | boolean | number[] | null
export type FormValues = Record<string, FormValue>

export interface SelectOption {
  label: string
  value: string | number | boolean
}

export interface EntityField {
  key: string
  label: string
  kind:
    | 'text'
    | 'password'
    | 'textarea'
    | 'number'
    | 'select'
    | 'switch'
    | 'date'
    | 'datetime'
    | 'time'
  required?: boolean
  placeholder?: string
  help?: string
  min?: number
  max?: number
  step?: number
  options?: SelectOption[]
  defaultValue?: FormValue
  multiple?: boolean
  requiredOnCreate?: boolean
  disabledOnEdit?: boolean
  valueFromRow?: (value: unknown, row: ApiEntity) => FormValue
}

export interface EntityColumn {
  key: string
  label: string
  minWidth?: number
  tag?: boolean
  formatter?: (value: unknown, row: ApiEntity) => string
}
