import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

const emptyModule = { id: 3, projectId: 1, name: '新建报表模块', ownerId: 2, ownerName: '李经理', nodes: [] }
function moduleGroup(page) { return page.locator('.work-group').filter({ has: page.getByRole('link', { name: emptyModule.name, exact: true }) }) }
function moduleCard(page) { return page.locator('.module-card').filter({ has: page.getByRole('link', { name: emptyModule.name, exact: true }) }) }

test('a newly created empty module appears immediately, survives reload and opens its work page', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  await page.route('**/api/projects/1/modules', route => {
    const body = route.request().postDataJSON()
    const module = { ...emptyModule, ...body }
    state.extraModules.push(module)
    return route.fulfill({ json: { code: 0, msg: 'ok', data: module } })
  })
  await page.goto('/projects/1/modules')
  await expect(page.getByRole('navigation', { name: '当前项目' }).getByRole('link', { name: '模块', exact: true })).toHaveAttribute('aria-current', 'page')
  await page.getByRole('button', { name: '新建模块', exact: true }).click()
  const form = page.getByRole('dialog', { name: '新建模块', exact: true })
  await form.getByRole('textbox', { name: '模块名称' }).fill(emptyModule.name)
  await form.getByRole('button', { name: '保存', exact: true }).click()
  await expect(form).toBeHidden()
  await expect(moduleCard(page)).toBeVisible()
  await page.getByRole('button', { name: '查看全部工作项', exact: true }).click()
  await expect(page).toHaveURL('/projects/1/work-items')
  await expect(moduleGroup(page)).toBeVisible()
  await expect(moduleGroup(page).getByText('本模块还没有工作项', { exact: true })).toBeVisible()
  await moduleGroup(page).getByRole('button', { name: '新建工作项', exact: true }).click()
  const workForm = page.getByRole('dialog', { name: '新建工作项', exact: true })
  await expect(workForm.getByRole('textbox', { name: '所属位置' })).toHaveValue(emptyModule.name)
  await workForm.getByRole('button', { name: '取消', exact: true }).click()
  await page.reload()
  await expect(moduleGroup(page)).toBeVisible()
  await moduleGroup(page).getByRole('link', { name: emptyModule.name, exact: true }).click()
  await expect(page).toHaveURL('/projects/1/modules/3')
  await expect(page.getByRole('navigation', { name: '当前项目' }).getByRole('link', { name: '模块', exact: true })).toHaveAttribute('aria-current', 'page')
  await expect(page.getByRole('heading', { name: emptyModule.name, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回模块列表', exact: true }).click()
  await expect(page).toHaveURL('/projects/1/modules')
  await expect(moduleCard(page)).toBeVisible()
})

test('the project overview shows modules before work items', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  state.extraModules.push(emptyModule)
  await page.goto('/projects/1/overview')
  const preview = page.getByRole('region', { name: '功能模块' })
  await expect(preview.getByRole('link', { name: /新建报表模块/ })).toBeVisible()
  await preview.getByRole('link', { name: /新建报表模块/ }).click()
  await expect(page).toHaveURL('/projects/1/modules/3')
})

test('module cards fit a 320px viewport without horizontal page overflow', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 })
  const state = await prepare(page, 'USER', 2)
  state.extraModules.push(emptyModule)
  await page.goto('/projects/1/modules')
  await expect(moduleCard(page)).toBeVisible()
  const width = await page.evaluate(() => ({ content: document.documentElement.scrollWidth, viewport: document.documentElement.clientWidth }))
  expect(width.content).toBeLessThanOrEqual(width.viewport)
})

test('empty modules respect location, risk and search filters', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  state.extraModules.push(emptyModule)
  await page.goto('/projects/1/work-items?risk=overdue')
  await expect(page.getByRole('heading', { name: '工作项', exact: true })).toBeVisible()
  await expect(moduleGroup(page)).toHaveCount(0)
  await page.getByRole('button', { name: '清除筛选', exact: true }).click()
  await expect(moduleGroup(page)).toBeVisible()
  await page.locator('.el-select').filter({ has: page.getByRole('combobox', { name: '所属位置', exact: true }) }).click()
  await page.getByRole('option', { name: emptyModule.name, exact: true }).click()
  await expect(page.locator('.work-group')).toHaveCount(1)
  await expect(moduleGroup(page)).toBeVisible()
  await expect(page.getByRole('heading', { name: '当前筛选下没有工作项', exact: true })).toHaveCount(0)
  await page.getByRole('textbox', { name: '搜索工作项', exact: true }).fill('不匹配的工作项')
  await expect(page.locator('.work-group')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '当前筛选下没有工作项', exact: true })).toBeVisible()
})

for (const persona of [{ role: 'VIEWER', archived: false }, { role: 'USER', archived: true }]) {
  test(`empty modules remain visible without creation actions for ${persona.role} archived=${persona.archived}`, async ({ page }) => {
    const state = await prepare(page, persona.role, 2, { archived: persona.archived })
    state.extraModules.push(emptyModule)
    await page.goto('/projects/1/modules')
    await expect(moduleCard(page)).toBeVisible()
    await expect(page.getByRole('button', { name: '新建模块', exact: true })).toHaveCount(0)
    await page.getByRole('button', { name: '查看全部工作项', exact: true }).click()
    await expect(moduleGroup(page)).toBeVisible()
    await expect(moduleGroup(page).getByRole('button', { name: '新建工作项', exact: true })).toHaveCount(0)
  })
}
