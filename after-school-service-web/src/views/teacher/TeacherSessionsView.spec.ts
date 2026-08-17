import { describe, expect, it } from 'vitest'

import source from './TeacherSessionsView.vue?raw'

describe('TeacherSessionsView destructive session updates', () => {
  it('confirms cancellation and freezes every close path while pending', () => {
    expect(source).toContain("sessionForm.status === 'CANCELED'")
    expect(source).toContain("'确认取消课次'")
    expect(source).toContain(':before-close="beforeSessionEditorClose"')
    expect(source).toContain(':close-on-press-escape="!sessionBusy"')
    expect(source).toContain(':show-close="!sessionBusy"')
    expect(source).toContain(':disabled="sessionBusy"')
  })
})
