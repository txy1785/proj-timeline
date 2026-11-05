import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

const WORK_ITEMS_URL = '/projects/1/work-items'
const ok = data => ({ status: 200, json: { code: 0, msg: 'ok', data } })

async function setup(page, onCreate = () => null) {
  const state = await prepare(page, 'ADMIN', 2)
  const posts = []
  const puts = []
  await page.route('**/api/projects/1/nodes', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = route.request().postDataJSON()
    posts.push(body)
    const outcome = onCreate(body, posts.filter(item => item.name === body.name).length)
    if (outcome === 'abort') return route.abort('timedout')
    if (outcome) return route.fulfill(outcome)
    return route.fulfill(ok({ id: 150 + posts.length, projectId: 1, status: 'NOT_STARTED', progress: 0, ...body }))
  })
  await page.route('**/api/nodes/*', async route => {
    if (route.request().method() !== 'PUT') return route.fallback()
    puts.push({ path: new URL(route.request().url()).pathname.slice(4), body: route.request().postDataJSON() })
    return route.fulfill(ok({}))
  })
  await page.goto(WORK_ITEMS_URL)
  await expect(page.getByRole('heading', { name: '工作项', exact: true })).toBeVisible()
  return { state, posts, puts }
}

const row = (page, name) => page.locator('tr.node-row').filter({ hasText: name })
const selectRow = (page, name) => row(page, name).locator('.el-checkbox').click()
const bulkButton = (page, name) => page.locator('.bulk-bar').getByRole('button', { name, exact: true })

test('批量改日期：未触碰的字段保持原值，按行校验结果日期，载荷只含所选字段', async ({ page }) => {
  const { puts } = await setup(page)
  await selectRow(page, '订单接口开发')
  await selectRow(page, '原功能工作项')
  await bulkButton(page, '日期').click()
  const dialog = page.getByRole('dialog', { name: '调整日期（2 项）' })
  await expect(dialog).toBeVisible()
  const confirm = dialog.getByRole('button', { name: '确认执行' })
  await expect(confirm).toBeDisabled()

  await dialog.getByText('设为完成', { exact: true }).click()
  const endInput = dialog.locator('.el-date-editor input')
  await endInput.fill('2026-10-10')
  await endInput.press('Enter')
  const orderRow = dialog.locator('.batch-preview li').filter({ hasText: '订单接口开发' })
  await expect(orderRow).toContainText('调整后开始 2026-10-19 晚于完成 2026-10-10')
  await expect(confirm).toBeDisabled()

  await endInput.fill('2026-11-10')
  await endInput.press('Enter')
  await expect(orderRow).toContainText('2026-10-19 ~ 2026-10-26 → 2026-10-19 ~ 2026-11-10')
  await expect(dialog.locator('.batch-preview li').filter({ hasText: '原功能工作项' })).toContainText('2026-10-01 ~ 2026-10-20 → 2026-10-01 ~ 2026-11-10')
  await dialog.getByRole('heading', { name: '调整日期（2 项）' }).click()
  await expect(page.locator('.el-picker__popper:visible')).toHaveCount(0)
  await confirm.click()
  await expect(dialog.getByText('成功', { exact: true })).toHaveCount(2)
  expect(puts).toEqual([
    { path: '/nodes/106', body: { planEnd: '2026-11-10' } },
    { path: '/nodes/107', body: { planEnd: '2026-11-10' } }
  ])
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).toBeHidden()

  await selectRow(page, '订单接口开发')
  await bulkButton(page, '日期').click()
  const second = page.getByRole('dialog', { name: '调整日期（1 项）' })
  await second.getByText('清空开始', { exact: true }).click()
  await expect(second.locator('.batch-preview li')).toContainText('2026-10-19 ~ 2026-10-26 → 未设 ~ 2026-10-26')
  await second.getByRole('button', { name: '确认执行' }).click()
  await expect(second.getByText('成功', { exact: true })).toBeVisible()
  expect(puts[2]).toEqual({ path: '/nodes/106', body: { planStart: null } })
})

test('快速新建：刷新后仍保留手动选择的位置，连续两次创建到同一模块', async ({ page }) => {
  const { posts } = await setup(page)
  const target = page.locator('.quick-create .el-select')
  await target.click()
  await page.getByRole('option', { name: '订单与支付' }).click()
  const title = page.getByRole('textbox', { name: '快速新建工作项' })
  for (const name of ['退款接口', '对账接口']) {
    const reloaded = page.waitForResponse(response => response.url().endsWith('/api/projects/1/timeline'))
    await title.fill(name)
    await title.press('Enter')
    await reloaded
    await expect(page.locator('.el-loading-mask')).toHaveCount(0)
    await expect(title).toHaveValue('')
    await expect(target).toContainText('订单与支付')
  }
  expect(posts).toEqual([{ name: '退款接口', moduleId: 2 }, { name: '对账接口', moduleId: 2 }])

  await page.locator('.filters-panel .el-select').first().click()
  await page.getByRole('option', { name: '项目级工作项' }).click()
  await expect(target).toContainText('项目级工作项')
})

test('批量新建：超时与业务码错误标为结果未知且不重试，HTTP 4xx 可重试', async ({ page }) => {
  const { posts } = await setup(page, (body, attempt) => {
    if (body.name === '超时项') return 'abort'
    if (body.name === '业务码项') return { status: 200, json: { code: 5000, msg: '写入异常', data: null } }
    if (body.name === '冲突项' && attempt === 1) return { status: 409, json: { code: 4090, msg: '名称重复', data: null } }
    return null
  })
  await page.getByRole('button', { name: '批量录入', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '批量录入工作项' })
  await dialog.getByRole('textbox', { name: '批量标题' }).fill('成功项\n超时项\n冲突项\n业务码项')
  await dialog.getByRole('button', { name: '开始录入' }).click()

  const item = name => dialog.locator('.batch-preview li').filter({ hasText: name })
  await expect(item('成功项')).toContainText('已录入')
  await expect(item('超时项')).toContainText('结果未知')
  await expect(item('超时项')).toContainText('可能已创建，请刷新核对')
  await expect(item('冲突项')).toContainText('名称重复')
  await expect(item('业务码项')).toContainText('结果未知')
  await expect(dialog.getByText('2 条可能已创建，请刷新核对，这些条目不会自动重试')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '刷新', exact: true })).toBeVisible()

  await dialog.getByRole('button', { name: '重试失败（1）' }).click()
  await expect(item('冲突项')).toContainText('已录入')
  await expect(dialog.getByRole('button', { name: /重试失败/ })).toHaveCount(0)
  expect(posts.map(body => body.name)).toEqual(['成功项', '超时项', '冲突项', '业务码项', '冲突项'])
  expect(posts[4]).toEqual(posts[2])
})

test('快速新建结果未知：保留输入，确认前 Enter 不再提交', async ({ page }) => {
  const { posts } = await setup(page, (body, attempt) => (body.name === '回归项' && attempt === 1 ? 'abort' : null))
  const title = page.getByRole('textbox', { name: '快速新建工作项' })
  await title.fill('回归项')
  await title.press('Enter')
  const warning = page.locator('.quick-unknown')
  await expect(warning).toContainText('「回归项」可能已创建，请刷新核对')
  await expect(title).toHaveValue('回归项')

  await title.press('Enter')
  await title.press('Enter')
  await expect(page.getByText('请先刷新核对「回归项」是否已创建').first()).toBeVisible()
  expect(posts).toHaveLength(1)

  await warning.getByRole('button', { name: '已核对，继续新建' }).click()
  await expect(warning).toHaveCount(0)
  await title.press('Enter')
  await expect(title).toHaveValue('')
  expect(posts.map(body => body.name)).toEqual(['回归项', '回归项'])
})

test('批量选择只作用于可见行：筛选或折叠后自动清除不可见选择', async ({ page }) => {
  await setup(page)
  await selectRow(page, '订单接口开发')
  await selectRow(page, '原功能工作项')
  await expect(page.getByText('已选 2 项')).toBeVisible()

  await page.getByRole('textbox', { name: '搜索工作项' }).fill('订单')
  await expect(row(page, '原功能工作项')).toHaveCount(0)
  await expect(page.getByText('已选 1 项')).toBeVisible()
  await bulkButton(page, '负责人').click()
  const dialog = page.getByRole('dialog', { name: '调整负责人（1 项）' })
  await expect(dialog.locator('.batch-preview li')).toHaveText([/订单接口开发/])
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()

  await page.getByRole('textbox', { name: '搜索工作项' }).fill('')
  await expect(row(page, '原功能工作项')).toBeVisible()
  await expect(page.getByText('已选 1 项')).toBeVisible()
  await expect(row(page, '原功能工作项').locator('.el-checkbox')).not.toHaveClass(/is-checked/)

  await page.locator('tbody').filter({ hasText: '订单与支付' }).locator('.group-toggle').click()
  await expect(row(page, '订单接口开发')).toHaveCount(0)
  await expect(page.locator('.bulk-bar')).toHaveCount(0)
  await page.locator('tbody').filter({ hasText: '订单与支付' }).locator('.group-toggle').click()
  await expect(row(page, '订单接口开发').locator('.el-checkbox')).not.toHaveClass(/is-checked/)
})
