import { computed, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { NoteRecord } from '../api/types'

/** 进展记录草稿由页面持有：记录区块因重新加载或出错卸载时，草稿与离开提示仍然有效。 */
export function useNoteDraft() {
  const state = reactive({ title: '', text: '', editingId: null as number | null, originalTitle: '', originalText: '' })
  const hasDraft = computed(() => state.editingId !== null
    ? state.title !== state.originalTitle || state.text !== state.originalText
    : state.text.trim().length > 0)

  function startEdit(note: NoteRecord) {
    state.editingId = note.id
    state.title = note.title || '进展记录.md'
    state.text = note.content
    state.originalTitle = state.title
    state.originalText = state.text
  }

  function reset() {
    Object.assign(state, { title: '', text: '', editingId: null, originalTitle: '', originalText: '' })
  }

  /**
   * 离开前确认。记录区块仍在且可写时可「保存并关闭」；区块已卸载（出错页）或不可写时只能放弃或留下复制。
   * `section` 为 NoteRecords 暴露的实例，`canWrite` 为当前是否可写入新记录。
   */
  async function allowLeave(section: { save: () => Promise<boolean>; uploading: number; saving: boolean; deleting: number } | undefined, canWrite: boolean): Promise<boolean> {
    if (section && section.uploading > 0) { ElMessage.info('附件正在上传，请稍候'); return false }
    if (section && (section.saving || section.deleting > 0)) { ElMessage.info('请先完成当前操作'); return false }
    if (!hasDraft.value) return true
    if (!section || (!canWrite && state.editingId === null)) {
      try {
        await ElMessageBox.confirm('当前已不可写入。请先复制草稿保留；关闭后将放弃本次输入。', '未保存的进展记录', {
          type: 'warning', confirmButtonText: '放弃并关闭', cancelButtonText: '返回保留草稿', distinguishCancelAndClose: true
        })
        return true
      } catch { return false }
    }
    try {
      await ElMessageBox.confirm('记录尚未保存。可以保存后关闭，或放弃本次输入；关闭此提示可继续编辑。', '未保存的进展记录', {
        type: 'warning', confirmButtonText: '保存并关闭', cancelButtonText: '放弃并关闭', distinguishCancelAndClose: true
      })
      return await section.save()
    } catch (action) { return action === 'cancel' }
  }

  return { state, hasDraft, startEdit, reset, allowLeave }
}

export type NoteDraft = ReturnType<typeof useNoteDraft>