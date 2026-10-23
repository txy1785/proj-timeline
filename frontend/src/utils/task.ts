import type { TaskKind, TaskStatus } from '../api/types'

/** 任务状态的中文标签，全站唯一来源（工作项详情、看板）。 */
export const TASK_STATUS_LABELS: Record<TaskStatus, string> = { TODO: '待完成', DOING: '进行中', DONE: '已完成' }

/** 任务状态对应的 Element Plus tag 类型。 */
export const TASK_STATUS_TYPES: Record<TaskStatus, 'info' | 'warning' | 'success'> = { TODO: 'info', DOING: 'warning', DONE: 'success' }

/** 任务状态顺序：看板列、状态下拉都按此顺序渲染。 */
export const TASK_STATUSES: TaskStatus[] = ['TODO', 'DOING', 'DONE']

/** 任务类型标签与 tag 类型。 */
export const TASK_KIND_LABELS: Record<TaskKind, string> = { FEATURE: '功能', FIX: '修复', BUG: '缺陷', TEST: '测试', OTHER: '其他' }
export const TASK_KIND_TYPES: Record<TaskKind, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = { FEATURE: 'primary', FIX: 'warning', BUG: 'danger', TEST: 'success', OTHER: 'info' }
export const TASK_KINDS: TaskKind[] = ['FEATURE', 'FIX', 'BUG', 'TEST', 'OTHER']

/** 列表快捷操作：按当前状态给出下一步动作（待完成→开始、进行中→完成、已完成→重新开始）。 */
export const TASK_NEXT_ACTION: Record<TaskStatus, { label: string; next: TaskStatus }> = {
  TODO: { label: '开始', next: 'DOING' },
  DOING: { label: '完成', next: 'DONE' },
  DONE: { label: '重新开始', next: 'DOING' }
}
