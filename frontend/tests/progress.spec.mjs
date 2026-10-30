import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

const doneModule = { id: 4, projectId: 1, name: '会员中心', ownerId: 2, ownerName: '李经理', nodes: [
  { id: 301, projectId: 1, moduleId: 4, name: '会员注册', type: 'FEATURE', status: 'DONE', ownerId: 2, ownerName: '李经理', planStart: '2026-10-06', planEnd: '2026-10-16', progress: 100, flags: { overdue: false, near: false, blocked: false } },
  { id: 302, projectId: 1, moduleId: 4, name: '会员积分', type: 'FEATURE', status: 'NOT_STARTED', ownerId: 2, ownerName: '李经理', planStart: '2026-11-02', planEnd: '2026-11-10', progress: 0, flags: { overdue: false, near: false, blocked: false } }
] }

/** 共享夹具里 107 计划 10-20 结束、今天 10-27 仍进行中，真实后端会标为延期；这里补齐该标记。 */
async function realisticTimeline(page, state, override = node => node) {
  state.extraModules.push(doneModule)
  await page.route('**/api/projects/1/timeline', async route => {
    const project = state.project()
    const fix = node => override(node.id === 107 ? { ...node, flags: { ...node.flags, overdue: true } } : node)
    const data = { ...project, modules: project.modules.map(module => ({ ...module, nodes: (module.nodes || []).map(fix) })), projectWorkItems: project.projectWorkItems.map(fix) }
    await route.fulfill({ json: { code: 0, msg: 'ok', data } })
  })
}

test('overview breaks the work due by today into delivered and overdue', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  await realisticTimeline(page, state)
  await page.goto('/projects/1/overview')
  const summary = page.getByRole('region', { name: '项目进度' })
  await expect(summary.getByText('落后 2 项', { exact: true })).toBeVisible()
  await expect(summary).toContainText('到今天应交付 3 项：已交付 1 项，延期 2 项')
  await expect(summary).toContainText('全部 4 项已交付 1 项')
  await expect(summary.getByRole('progressbar', { name: '时间进度' })).toBeVisible()
  const card = page.getByRole('region', { name: '功能模块' }).getByRole('link', { name: /订单与支付/ })
  await expect(card).toContainText('1 项延期')
  await summary.getByRole('link', { name: '查看延期 →' }).click()
  await expect(page).toHaveURL('/projects/1/work-items?risk=overdue')
})

test('timeline marks overdue bars red, shows module progress and scrolls to today', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  await realisticTimeline(page, state)
  await page.goto('/projects/1/timeline')
  await expect(page.getByRole('region', { name: '项目进度' })).toContainText('已交付 1 项，延期 2 项')
  await expect(page.locator('.date-bar.overdue')).toHaveCount(2)
  await expect(page.locator('.date-bar.overdue').filter({ hasText: '10/19' })).toContainText('延期 1 天')
  await expect(page.locator('.date-bar.overdue').filter({ hasText: '10/01' })).toContainText('延期 7 天')
  await expect(page.locator('.schedule-group').filter({ hasText: '会员中心' })).toContainText('已交付 1/2')
  await expect(page.locator('.schedule-group').filter({ hasText: '订单与支付' })).toContainText('延期 1')
  const scrolled = await page.locator('.schedule-scroll').evaluate(element => element.scrollLeft)
  expect(scrolled).toBeGreaterThan(0)
})

test('a project whose work items are all cancelled says so instead of asking to create them', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  await realisticTimeline(page, state, node => ({ ...node, status: 'CANCELLED' }))
  await page.goto('/projects/1/overview')
  const summary = page.getByRole('region', { name: '项目进度' })
  await expect(summary.getByText('尚无有效工作项', { exact: true })).toBeVisible()
  await expect(summary).toContainText('现有工作项均已取消')
  await expect(summary.getByRole('progressbar')).toHaveCount(0)
})