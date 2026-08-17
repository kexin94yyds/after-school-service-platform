import { describe, expect, it } from 'vitest'

import source from './AcademicGovernanceView.vue?raw'

describe('AcademicGovernanceView term actions', () => {
  it('only offers DRAFT when creating a term', () => {
    expect(source).toContain("if (!current) {\n    return [{ label: '草稿', value: 'DRAFT' }]")
  })

  it('keeps selection and editing as sibling native buttons', () => {
    const termTrack = source.match(
      /<div v-loading="baseLoading" class="term-track">([\s\S]*?)<div v-if="!baseLoading/,
    )?.[1]

    expect(termTrack).toBeDefined()
    expect(termTrack).toContain('<article')
    expect(termTrack).toContain('class="term-stop"')
    expect(termTrack).toContain('class="term-select"')
    expect(termTrack).toContain('class="term-edit"')
    expect(termTrack).toContain(':aria-pressed="filters.termId === term.id"')
    expect(termTrack).toContain(':aria-label="`\u7f16\u8f91\u5b66\u671f\uff1a${term.termName}`"')
    expect(termTrack).not.toContain('role="button"')
    expect(termTrack?.match(/<button\b/g)).toHaveLength(2)
    expect(termTrack).toMatch(
      /class="term-select"[\s\S]*?<\/button>\s*<button[\s\S]*?class="term-edit"/,
    )
  })
})
