import type { WorkItemType } from '../api/types'

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
