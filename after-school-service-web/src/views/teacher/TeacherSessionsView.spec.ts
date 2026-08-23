import { describe, expect, it } from 'vitest'

import source from './TeacherSessionsView.vue?raw'

describe('TeacherSessionsView schedule-owned session status', () => {
  it('allows teaching notes without exposing a session status write', () => {
    expect(source).not.toContain('sessionForm.status')
    expect(source).not.toContain('确认取消课次')
    expect(source).toContain('课次状态由排课与考勤流程维护')
    expect(source).toContain('sessionForm.notes.trim() || null')
    expect(source).toContain(':before-close="beforeSessionEditorClose"')
    expect(source).toContain(':close-on-press-escape="!sessionBusy"')
    expect(source).toContain(':show-close="!sessionBusy"')
    expect(source).toContain(':disabled="sessionBusy"')
  })
})
