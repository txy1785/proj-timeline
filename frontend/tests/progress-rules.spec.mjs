import { test, expect } from '@playwright/test'
import { deliveredPercent, scheduleProgress, scheduleVerdict } from '../src/utils/progress.ts'

const node = (status, planEnd, overdue = false) => ({ status, planEnd, flags: { overdue, near: false, blocked: false } })

test('due work splits exactly into delivered and overdue', () => {
  const progress = scheduleProgress([node('DONE', '2026-10-10'), node('IN_PROGRESS', '2026-10-20', true), node('DONE', '2026-11-05'), node('CANCELLED', '2026-10-01')], '2026-10-27', { planStart: '2026-10-01', planEnd: '2026-11-30' })
  expect(progress).toMatchObject({ total: 3, done: 2, due: 2, dueDone: 1, behind: 1 })
  expect(progress.dueDone + progress.behind).toBe(progress.due)
})

test('unknown business date hides the comparison instead of guessing', () => {
  const progress = scheduleProgress([node('IN_PROGRESS', '2026-10-20', true)], '', { planStart: '2026-10-01', planEnd: '2026-11-30' })
  expect(progress.due).toBe(0)
  expect(progress.timePercent).toBeUndefined()
})

test('time progress is clamped and omitted without a project end date', () => {
  expect(scheduleProgress([], '2026-09-01', { planStart: '2026-10-01', planEnd: '2026-10-10' }).timePercent).toBe(0)
  expect(scheduleProgress([], '2026-12-01', { planStart: '2026-10-01', planEnd: '2026-10-10' }).timePercent).toBe(100)
  expect(scheduleProgress([], '2026-10-27', { planStart: '2026-10-01' }).timePercent).toBeUndefined()
})

test('all cancelled work has no verdict to report', () => {
  expect(scheduleVerdict(scheduleProgress([node('CANCELLED', '2026-10-01')], '2026-10-27')).label).toBe('尚无有效工作项')
})

test('delivered percent matches backend rounding and never shows 100 before everything is delivered', () => {
  expect(deliveredPercent(199, 200)).toBe(99)
  expect(deliveredPercent(2, 2)).toBe(100)
  expect(deliveredPercent(0, 0)).toBe(0)
})
