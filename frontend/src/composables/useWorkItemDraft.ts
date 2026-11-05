import { ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'

export interface StoredDraft<T> { savedAt: string; title: string; snapshot: T }
/** 提交时固定的草稿键与快照，响应返回时界面可能已换账号或换表单。 */
export interface SubmittedDraft { key: string; snapshot: string }

/** 草稿按账号、项目、新建/编辑对象隔离，避免串号或串项目恢复。 */
export function workItemDraftKey(accountId: number, projectId: number, workItemId: number) {
  return `timeline-work-item-draft:${accountId}:${projectId}:${workItemId || 'new'}`
}

/** 只在表单激活且已处理旧草稿（含损坏草稿）后写入；与打开时的基线一致则视为干净并移除存储。 */
export function useWorkItemDraft<T>(isSnapshot: (value: unknown) => value is T) {
  /** shallowRef 保留泛型 T 原样，ref 会将其解包为 UnwrapRef<T>；草稿整体替换，无需深层响应。 */
  const pending = shallowRef<StoredDraft<T> | null>(null)
  const corrupt = ref(false)
  const dirty = ref(false)
  const storageFailed = ref(false)
  let key: string | null = null
  let baseline = ''

  function reportFailure(error: unknown) {
    if (!storageFailed.value) ElMessage.error(`草稿暂存失败，当前输入仍保留在表单中，请勿关闭或刷新页面：${(error as Error).message}`)
    storageFailed.value = true
  }

  function write(record: StoredDraft<T>) {
    if (!key) return
    try {
      sessionStorage.setItem(key, JSON.stringify(record))
      storageFailed.value = false
    } catch (error) { reportFailure(error) }
  }

  function remove() {
    if (!key) return
    try { sessionStorage.removeItem(key) } catch (error) { reportFailure(error) }
  }

  function isStoredDraft(value: unknown): value is StoredDraft<T> {
    if (typeof value !== 'object' || value === null) return false
    const record = value as Record<string, unknown>
    return typeof record.savedAt === 'string' && !Number.isNaN(Date.parse(record.savedAt))
      && typeof record.title === 'string' && isSnapshot(record.snapshot)
  }

  function parse(raw: string) {
    try {
      const value: unknown = JSON.parse(raw)
      return isStoredDraft(value) ? value : null
    } catch { return null }
  }

  function read(storageKey: string) {
    let raw: string | null
    try { raw = sessionStorage.getItem(storageKey) } catch {
      ElMessage.warning('读取已暂存的草稿失败，已按初始内容打开表单')
      return null
    }
    if (!raw) return null
    const record = parse(raw)
    if (!record) corrupt.value = true
    return record
  }

  function start(storageKey: string, snapshot: T) {
    key = storageKey
    baseline = JSON.stringify(snapshot)
    dirty.value = false
    storageFailed.value = false
    corrupt.value = false
    pending.value = read(storageKey)
  }

  function stop() {
    key = null
    pending.value = null
    corrupt.value = false
    dirty.value = false
  }

  function persist(snapshot: T, title: string) {
    if (!key || pending.value || corrupt.value) return
    dirty.value = JSON.stringify(snapshot) !== baseline
    if (dirty.value) write({ savedAt: new Date().toISOString(), title, snapshot })
    else remove()
  }

  function restore() {
    const snapshot = pending.value?.snapshot ?? null
    pending.value = null
    return snapshot
  }

  function discardPending() {
    remove()
    pending.value = null
  }

  function discardCorrupt() {
    remove()
    corrupt.value = false
  }

  function resetBaseline(snapshot: T) {
    baseline = JSON.stringify(snapshot)
    dirty.value = false
    remove()
  }

  function clear() {
    remove()
    stop()
  }

  function captureSubmitted(snapshot: T): SubmittedDraft | null {
    return key ? { key, snapshot: JSON.stringify(snapshot) } : null
  }

  /** 只删除仍等于提交快照的草稿：提交后又产生的新编辑不能被旧响应误删。 */
  function clearSubmitted(submitted: SubmittedDraft | null) {
    if (!submitted) return
    try {
      const raw = sessionStorage.getItem(submitted.key)
      const record = raw ? parse(raw) : null
      if (record && JSON.stringify(record.snapshot) === submitted.snapshot) sessionStorage.removeItem(submitted.key)
    } catch { /* 清理失败只留下可手动放弃的旧草稿，不影响已保存结果 */ }
    if (submitted.key === key && pending.value && JSON.stringify(pending.value.snapshot) === submitted.snapshot) pending.value = null
  }

  return {
    pending, corrupt, dirty, storageFailed, start, stop, persist, restore, discardPending, discardCorrupt, resetBaseline, clear,
    captureSubmitted, clearSubmitted
  }
}
