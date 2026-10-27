import type { TaskStatus } from '../api/types'

export const BUG_STATUS_LABELS: Record<TaskStatus, string> = { TODO: '待修复', DOING: '修复中', DONE: '已解决' }
export const BUG_NEXT_ACTION: Record<TaskStatus, { label: string; next: TaskStatus }> = {
  TODO: { label: '开始修复', next: 'DOING' },
  DOING: { label: '标记已解决', next: 'DONE' },
  DONE: { label: '重新打开', next: 'TODO' }
}
