import type { NodeItem } from '../api/types'
import { daysBetween } from './date'
import { hasWorkItemRisk, workItemCounts } from './workItem'

type ScheduleNode = Pick<NodeItem, 'status' | 'planEnd' | 'flags'>

export interface ScheduleProgress {
  total: number
  done: number
  /** 计划完成日早于业务今天的未取消项；其中已交付 dueDone 项，其余即延期 behind 项。 */
  due: number
  dueDone: number
  /** 未关闭且已延期的项，与风险列表同口径。 */
  behind: number
  donePercent: number
  /** 项目计划时间已过去的比例；完成日期未定或业务日期未知时为 undefined。 */
  timePercent?: number
}

/** 进度对照计划：到今天应交付的项拆成已交付与延期，进行中不估算完成量。 */
export function scheduleProgress(nodes: ScheduleNode[], today: string, plan?: { planStart: string; planEnd?: string }): ScheduleProgress {
  const { total, done } = workItemCounts(nodes)
  const dueNodes = today ? nodes.filter(node => node.status !== 'CANCELLED' && node.planEnd < today) : []
  const dueDone = dueNodes.filter(node => node.status === 'DONE').length
  const behind = nodes.filter(node => hasWorkItemRisk(node, 'overdue')).length
  return { total, done, due: dueNodes.length, dueDone, behind, donePercent: deliveredPercent(done, total), timePercent: timeElapsed(plan, today) }
}

export function scheduleVerdict(progress: Pick<ScheduleProgress, 'total' | 'done' | 'behind'>): { label: string; tone: 'success' | 'danger' | 'info' } {
  if (!progress.total) return { label: '尚无有效工作项', tone: 'info' }
  if (progress.done === progress.total) return { label: '全部已交付', tone: 'success' }
  if (progress.behind) return { label: `落后 ${progress.behind} 项`, tone: 'danger' }
  return { label: '按计划进行', tone: 'success' }
}

/** 与后端 ProgressRules.aggregate 一致：未全部交付时最多 99%。 */
export function deliveredPercent(done: number, total: number): number {
  if (!total) return 0
  return done === total ? 100 : Math.min(99, Math.round(done * 100 / total))
}

function timeElapsed(plan: { planStart: string; planEnd?: string } | undefined, today: string): number | undefined {
  if (!plan?.planEnd || !today) return undefined
  const span = daysBetween(plan.planStart, plan.planEnd) + 1
  const passed = daysBetween(plan.planStart, today)
  return span > 0 ? Math.min(100, Math.max(0, Math.round(passed * 100 / span))) : undefined
}