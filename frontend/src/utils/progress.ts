import type { NodeItem } from '../api/types'
import { daysBetween } from './date'
import { hasWorkItemRisk, workItemCounts } from './workItem'

type ScheduleNode = Pick<NodeItem, 'status' | 'planEnd' | 'flags'>

export interface ScheduleProgress {
  total: number
  done: number
  /** 计划结束日早于业务今天、按计划此刻应已交付的未取消项。 */
  expected: number
  /** 未关闭且已延期的项，与风险列表同口径。 */
  behind: number
  donePercent: number
  /** 项目计划时间已过去的比例；完成日期未定时为 undefined。 */
  timePercent?: number
}

/** 进度对照计划：已交付数与“到今天应交付数”比较，进行中不估算完成量。 */
export function scheduleProgress(nodes: ScheduleNode[], today: string, plan?: { planStart: string; planEnd?: string }): ScheduleProgress {
  const { total, done } = workItemCounts(nodes)
  const active = nodes.filter(node => node.status !== 'CANCELLED')
  const expected = today ? active.filter(node => node.planEnd < today).length : 0
  const behind = active.filter(node => hasWorkItemRisk(node, 'overdue')).length
  return { total, done, expected, behind, donePercent: percent(done, total), timePercent: timeElapsed(plan, today) }
}

export function scheduleVerdict(progress: ScheduleProgress): { label: string; tone: 'success' | 'danger' | 'info' } {
  if (!progress.total) return { label: '尚无工作项', tone: 'info' }
  if (progress.done === progress.total) return { label: '全部已交付', tone: 'success' }
  if (progress.behind) return { label: `落后 ${progress.behind} 项`, tone: 'danger' }
  return { label: '按计划进行', tone: 'success' }
}

function percent(part: number, whole: number): number {
  return whole ? Math.round(part * 100 / whole) : 0
}

function timeElapsed(plan: { planStart: string; planEnd?: string } | undefined, today: string): number | undefined {
  if (!plan?.planEnd || !today) return undefined
  const span = daysBetween(plan.planStart, plan.planEnd) + 1
  const passed = daysBetween(plan.planStart, today)
  return span > 0 ? Math.min(100, Math.max(0, percent(passed, span))) : undefined
}
