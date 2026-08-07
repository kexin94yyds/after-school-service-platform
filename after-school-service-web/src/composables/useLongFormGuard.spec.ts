import { describe, expect, it, vi } from 'vitest'

vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus/es/components/message-box/style/css', () => ({}))

import {
  guardLongFormExit,
  longFormSnapshot,
  shouldBlockLongFormUnload,
} from './useLongFormGuard'

describe('long form guard', () => {
  it('compares form snapshots by submitted values', () => {
    expect(longFormSnapshot({ name: '张老师', enabled: true })).toBe(
      longFormSnapshot({ name: '张老师', enabled: true }),
    )
    expect(longFormSnapshot({ name: '李老师', enabled: true })).not.toBe(
      longFormSnapshot({ name: '张老师', enabled: true }),
    )
  })

  it('keeps a dirty form open when discard is canceled', async () => {
    const confirmDiscard = vi.fn().mockRejectedValue(new Error('cancel'))

    await expect(
      guardLongFormExit(true, false, confirmDiscard, vi.fn()),
    ).resolves.toBe(false)
    expect(confirmDiscard).toHaveBeenCalledOnce()
  })

  it('allows a confirmed discard and skips confirmation for a clean form', async () => {
    const confirmDiscard = vi.fn().mockResolvedValue('confirm')

    await expect(
      guardLongFormExit(true, false, confirmDiscard, vi.fn()),
    ).resolves.toBe(true)
    await expect(
      guardLongFormExit(false, false, confirmDiscard, vi.fn()),
    ).resolves.toBe(true)
    expect(confirmDiscard).toHaveBeenCalledOnce()
  })

  it('blocks every close path while a save is running', async () => {
    const confirmDiscard = vi.fn()
    const notifySaving = vi.fn()

    await expect(
      guardLongFormExit(true, true, confirmDiscard, notifySaving),
    ).resolves.toBe(false)
    expect(confirmDiscard).not.toHaveBeenCalled()
    expect(notifySaving).toHaveBeenCalledOnce()
  })

  it('blocks browser unload only for a visible dirty or saving form', () => {
    expect(shouldBlockLongFormUnload(true, true, false)).toBe(true)
    expect(shouldBlockLongFormUnload(true, false, true)).toBe(true)
    expect(shouldBlockLongFormUnload(true, false, false)).toBe(false)
    expect(shouldBlockLongFormUnload(false, true, true)).toBe(false)
  })
})
