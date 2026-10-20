import type { TaskStatus } from '../api/types'

/** 任务状态的中文标签，全站唯一来源（节点详情、看板）。 */
export const TASK_STATUS_LABELS: Record<TaskStatus, string> = { TODO: '待完成', DOING: '进行中', DONE: '已完成' }

/** 任务状态对应的 Element Plus tag 类型。 */
export const TASK_STATUS_TYPES: Record<TaskStatus, 'info' | 'warning' | 'success'> = { TODO: 'info', DOING: 'warning', DONE: 'success' }

/** 任务状态顺序：看板列、状态下拉都按此顺序渲染。 */
export const TASK_STATUSES: TaskStatus[] = ['TODO', 'DOING', 'DONE']
