import { describe, expect, it } from 'vitest'

import source from './CourseOfferingView.vue?raw'

describe('CourseOfferingView long form wiring', () => {
  it('only offers DRAFT when creating an offering', () => {
    expect(source).toContain('if (editingOffering.value === null)')
    expect(source).toContain("return [{ value: 'DRAFT', label: statusLabel('DRAFT') }]")
  })

  it('uses the shared guard and freezes the entire form while saving', () => {
    const dialog = source.match(
      /<el-dialog\s+[\s\S]*?v-model="offeringDialogVisible"[\s\S]*?<\/el-dialog>/,
    )?.[0]

    expect(source).toContain("import { useLongFormGuard } from '@/composables/useLongFormGuard'")
    expect(source).toContain('} = useLongFormGuard({')
    expect(dialog).toBeDefined()
    expect(dialog).toContain(':before-close="beforeOfferingDialogClose"')
    expect(dialog).toContain(':close-on-press-escape="!offeringSaving"')
    expect(dialog).toContain(':show-close="!offeringSaving"')
    expect(dialog).toContain(':disabled="offeringSaving"')
    expect(dialog).toMatch(
      /:disabled="\s*offeringSaving \|\| offeringForm\.termId === null\s*"/,
    )
    expect(dialog).toMatch(
      /:disabled="\s*offeringSaving \|\|\s*\(offeringForm\.planId === null &&\s*offeringForm\.roomId === null\)\s*"/,
    )
    expect(dialog).toContain('@click="requestOfferingDialogClose"')
    expect(source.match(/captureOfferingBaseline\(\)/g)).toHaveLength(3)
  })
})
