import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

const doneModule = { id: 4, projectId: 1, name: '会员中心', ownerId: 2, ownerName: '李经理', nodes: [
  { id: 301, projectId: 1, moduleId: 4, name: '会员注册', type: 'FEATURE', status: 'DONE', ownerId: 2, ownerName: '李经理', planStart: '2026-10-06', planEnd: '2026-10-16', progress: 100, flags: { overdue: false, near: false, blocked: false } },
  { id: 302, projectId: 1, moduleId: 4, name: '会员积分', type: 'FEATURE', status: 'NOT_STARTED', ownerId: 2, ownerName: '李经理', planStart: '2026-11-02', planEnd: '2026-11-10', progress: 0, flags: { overdue: false, near: false, blocked: false } }
] }

test('overview compares delivered work with what the plan expects by today', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  state.extraModules.push(doneModule)
  await page.goto('/projects/1/overview')
  const summary = page.getByRole('region', { name: '项目进度' })
  await expect(summary.getByText('落后 1 项', { exact: true })).toBeVisible()
  await expect(summary).toContainText('按计划到今天应交付 3 项，实际已交付 1 项（共 4 项）')
  await expect(summary.getByText('时间进度')).toBeVisible()
  const card = page.getByRole('region', { name: '功能模块' }).getByRole('link', { name: /订单与支付/ })
  await expect(card).toContainText('1 项延期')
  await summary.getByRole('link', { name: '查看 1 项延期 →' }).click()
  await expect(page).toHaveURL('/projects/1/work-items?risk=overdue')
})

test('timeline marks overdue bars red, shows module progress and scrolls to today', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  state.extraModules.push(doneModule)
  await page.goto('/projects/1/timeline')
  await expect(page.getByRole('region', { name: '项目进度' })).toContainText('实际已交付 1 项')
  await expect(page.locator('.date-bar.overdue')).toHaveCount(1)
  await expect(page.locator('.date-bar.overdue')).toContainText('延期 1 天')
  await expect(page.locator('.schedule-group').filter({ hasText: '会员中心' })).toContainText('已交付 1/2')
  await expect(page.locator('.schedule-group').filter({ hasText: '订单与支付' })).toContainText('延期 1')
  const scrolled = await page.locator('.schedule-scroll').evaluate(element => element.scrollLeft)
  expect(scrolled).toBeGreaterThan(0)
})
