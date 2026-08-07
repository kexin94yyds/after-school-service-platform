import { ElMessage, ElMessageBox } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import {
  computed,
  onBeforeUnmount,
  onMounted,
  ref,
  type ComputedRef,
  type Ref,
} from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

export const UNSAVED_LONG_FORM_MESSAGE =
  '当前表单有未保存的修改。放弃修改并继续吗？'
export const SAVING_LONG_FORM_MESSAGE = '正在保存，请稍候。'

type DialogDone = (cancel?: boolean) => void

interface LongFormGuardOptions {
  visible: Ref<boolean>
  saving: Ref<boolean>
  snapshot: () => unknown
}

interface LongFormGuard {
  isDirty: ComputedRef<boolean>
  captureBaseline: () => void
  requestClose: () => Promise<boolean>
  beforeClose: (done: DialogDone) => Promise<void>
}

export function longFormSnapshot(value: unknown): string {
  return JSON.stringify(value)
}

export function shouldBlockLongFormUnload(
  visible: boolean,
  dirty: boolean,
  saving: boolean,
): boolean {
  return visible && (dirty || saving)
}

export async function guardLongFormExit(
  dirty: boolean,
  saving: boolean,
  confirmDiscard: () => Promise<unknown>,
  notifySaving: () => void,
): Promise<boolean> {
  if (saving) {
    notifySaving()
    return false
  }
  if (!dirty) return true

  try {
    await confirmDiscard()
    return true
  } catch {
    return false
  }
}

export function useLongFormGuard(
  options: LongFormGuardOptions,
): LongFormGuard {
  const baseline = ref(longFormSnapshot(options.snapshot()))
  const isDirty = computed(
    () =>
      options.visible.value &&
      longFormSnapshot(options.snapshot()) !== baseline.value,
  )
  let pendingConfirmation: Promise<boolean> | null = null

  function captureBaseline(): void {
    baseline.value = longFormSnapshot(options.snapshot())
  }

  function confirmExit(): Promise<boolean> {
    if (pendingConfirmation) return pendingConfirmation

    pendingConfirmation = guardLongFormExit(
      isDirty.value,
      options.saving.value,
      () =>
        ElMessageBox.confirm(
          UNSAVED_LONG_FORM_MESSAGE,
          '放弃未保存修改',
          {
            confirmButtonText: '放弃修改',
            cancelButtonText: '继续编辑',
            type: 'warning',
          },
        ),
      () => ElMessage.warning(SAVING_LONG_FORM_MESSAGE),
    ).finally(() => {
      pendingConfirmation = null
    })

    return pendingConfirmation
  }

  async function requestClose(): Promise<boolean> {
    if (!(await confirmExit())) return false
    options.visible.value = false
    return true
  }

  async function beforeClose(done: DialogDone): Promise<void> {
    if (await confirmExit()) done()
  }

  function handleBeforeUnload(event: BeforeUnloadEvent): void {
    if (
      !shouldBlockLongFormUnload(
        options.visible.value,
        isDirty.value,
        options.saving.value,
      )
    ) {
      return
    }
    event.preventDefault()
    event.returnValue = ''
  }

  onBeforeRouteLeave(() => confirmExit())
  onMounted(() => window.addEventListener('beforeunload', handleBeforeUnload))
  onBeforeUnmount(() =>
    window.removeEventListener('beforeunload', handleBeforeUnload),
  )

  return {
    isDirty,
    captureBaseline,
    requestClose,
    beforeClose,
  }
}
