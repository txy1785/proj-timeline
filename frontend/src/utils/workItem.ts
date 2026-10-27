import type { NodeStatus, WorkItemType } from '../api/types'

/** 工作项类型的中文标签，全站唯一来源。 */
export const WORK_ITEM_TYPE_LABELS: Record<WorkItemType, string> = {
  FEATURE: '功能开发',
  TEST: '测试',
  MAINTENANCE: '维护',
  BUGFIX: '缺陷修复',
  OTHER: '其他'
}

export const WORK_ITEM_TYPE_TYPES: Record<WorkItemType, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  FEATURE: 'primary',
  TEST: 'success',
  MAINTENANCE: 'warning',
  BUGFIX: 'danger',
  OTHER: 'info'
}

export const WORK_ITEM_TYPES: WorkItemType[] = ['FEATURE', 'TEST', 'MAINTENANCE', 'BUGFIX', 'OTHER']

export const NODE_STATUS_LABELS: Record<NodeStatus, string> = {
  NOT_STARTED: '未开始', IN_PROGRESS: '进行中', DONE: '已完成', CANCELLED: '已取消'
}
export const NODE_STATUS_TYPES: Record<NodeStatus, 'primary' | 'success' | 'info'> = {
  NOT_STARTED: 'info', IN_PROGRESS: 'primary', DONE: 'success', CANCELLED: 'info'
}
