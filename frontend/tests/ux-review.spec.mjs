import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

const ADMIN = 'ADMIN'
const WORK_ITEMS_URL = '/projects/1/work-items'

async function setup(page) {
  const state = await prepare(page, ADMIN, 2)
  const created = []
  await page.route('**/api/projects/1/nodes', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = route.request().postDataJSON()
    created.push(body)
    await route.fulfill({ status: 200, json: { code: 0, msg: 'ok', data: { id: 150, projectId: 1, status: 'NOT_STARTED', progress: 0, ...body } } })
  })
  await page.goto(WORK_ITEMS_URL)
  return { state, created }
}

async function openNewDialog(page) {
  await page.getByRole('button', { name: /新建工作项/ }).first().click()
  const dialog = page.getByRole('dialog', { name: '新建工作项' })
  await expect(dialog).toBeVisible()
  return dialog
}

const nameInput = dialog => dialog.getByPlaceholder('例如：支付渠道接入')
const descriptionInput = dialog => dialog.getByPlaceholder('补充交付范围或验收说明（可选）')
const formItem = (dialog, label) => dialog.locator('.el-form-item').filter({ hasText: label })

async function closeKeepingDraft(page, dialog) {
  await dialog.locator('.el-dialog__headerbtn').click()
  await page.getByRole('button', { name: '保留草稿并关闭' }).click()
  await expect(dialog).toBeHidden()
}

async function restorePendingDraft(dialog) {
  await expect(dialog.getByText('发现未保存的草稿「草稿工作项」')).toBeVisible()
  await dialog.getByRole('button', { name: '恢复草稿' }).click()
  await expect(nameInput(dialog)).toHaveValue('草稿工作项')
  await expect(descriptionInput(dialog)).toHaveValue('草稿说明内容')
}

const draftKey = accountId => `timeline-work-item-draft:${accountId}:1:new`
const readDraft = (page, key) => page.evaluate(storageKey => sessionStorage.getItem(storageKey), key)

test('项目级草稿在模块固定位置的表单中恢复，保存按模块提交', async ({ page }) => {
  const { created } = await setup(page)
  let dialog = await openNewDialog(page)
  await expect(formItem(dialog, '所属位置').locator('.el-select')).toContainText('项目级工作项')
  await nameInput(dialog).fill('草稿工作项')
  await descriptionInput(dialog).fill('草稿说明内容')
  await closeKeepingDraft(page, dialog)

  await page.locator('.group-row').filter({ hasText: '订单与支付' }).getByRole('button', { name: '新建工作项' }).click()
  dialog = page.getByRole('dialog', { name: '新建工作项' })
  await restorePendingDraft(dialog)
  const fixedLocation = formItem(dialog, '所属位置').locator('input')
  await expect(fixedLocation).toHaveValue('订单与支付')
  await expect(fixedLocation).toBeDisabled()
  await dialog.getByRole('button', { name: '保存', exact: true }).click()

  await expect(dialog).toBeHidden()
  expect(created).toHaveLength(1)
  expect(created[0]).toMatchObject({ name: '草稿工作项', description: '草稿说明内容', moduleId: 2 })
  expect(await readDraft(page, draftKey(2))).toBeNull()
})

test('保存在途时切换账号：成功响应只清除原账号的提交草稿，不触碰新账号草稿与界面', async ({ page }) => {
  await prepare(page, ADMIN, 2)
  const created = []
  let release
  const gate = new Promise(resolve => { release = resolve })
  await page.route('**/api/projects/1/nodes', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    const body = route.request().postDataJSON()
    created.push(body)
    await gate
    await route.fulfill({ status: 200, json: { code: 0, msg: 'ok', data: { id: 151, projectId: 1, status: 'NOT_STARTED', progress: 0, ...body } } })
  })
  await page.goto(WORK_ITEMS_URL)

  let dialog = await openNewDialog(page)
  await nameInput(dialog).fill('在途工作项')
  await descriptionInput(dialog).fill('在途说明')
  const requestSeen = page.waitForRequest(req => req.method() === 'POST' && req.url().endsWith('/api/projects/1/nodes'))
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await requestSeen
  expect(JSON.parse(await readDraft(page, draftKey(2))).title).toBe('在途工作项')

  await page.evaluate(async () => {
    const vueApp = document.querySelector('#app').__vue_app__
    const pinia = vueApp.config.globalProperties.$pinia
    const auth = pinia._s.get('auth')
    auth.user = { ...auth.user, id: 4, name: '赵工' }
    // A real login re-enters the route and reloads context; this in-place swap must restore it explicitly.
    await vueApp._instance.proxy.$nextTick()
    await pinia._s.get('project-context').load(1, true)
  })
  await expect(dialog).toBeHidden()

  dialog = await openNewDialog(page)
  await expect(dialog.getByText(/发现未保存的草稿/)).toHaveCount(0)
  await nameInput(dialog).fill('新账号草稿')
  await closeKeepingDraft(page, dialog)
  const newAccountDraft = await readDraft(page, draftKey(4))
  expect(JSON.parse(newAccountDraft).title).toBe('新账号草稿')

  const responded = page.waitForResponse(res => res.request().method() === 'POST' && res.url().endsWith('/api/projects/1/nodes'))
  release()
  await responded
  await expect.poll(() => readDraft(page, draftKey(2))).toBeNull()

  expect(await readDraft(page, draftKey(4))).toBe(newAccountDraft)
  await expect(dialog).toBeHidden()
  await expect(page.getByText('工作项已创建，可补充交付资料并开始工作')).toHaveCount(0)
  expect(created).toHaveLength(1)
  expect(created[0]).toMatchObject({ name: '在途工作项', description: '在途说明' })
})

test('仅填名称即可创建，计划日期保持未知并停留在列表', async ({ page }) => {
  const { created } = await setup(page)
  const dialog = await openNewDialog(page)
  await nameInput(dialog).fill('支付渠道接入')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()

  await expect(dialog).toBeHidden()
  await expect(page).toHaveURL(new RegExp(`${WORK_ITEMS_URL}$`))
  expect(created).toHaveLength(1)
  expect(created[0]).toMatchObject({ name: '支付渠道接入', planStart: null, planEnd: null })
})

test('关闭时保留草稿，重新打开与刷新后都能恢复', async ({ page }) => {
  const { created } = await setup(page)
  let dialog = await openNewDialog(page)
  await nameInput(dialog).fill('草稿工作项')
  await descriptionInput(dialog).fill('草稿说明内容')
  await closeKeepingDraft(page, dialog)

  dialog = await openNewDialog(page)
  await restorePendingDraft(dialog)
  await closeKeepingDraft(page, dialog)

  await page.reload()
  dialog = await openNewDialog(page)
  await restorePendingDraft(dialog)
  expect(created).toHaveLength(0)
})

test('录入已完成的历史成果只提交实际完成日期，不补齐计划日期', async ({ page }) => {
  const { created } = await setup(page)
  const dialog = await openNewDialog(page)
  await nameInput(dialog).fill('旧版订单导出')
  await dialog.getByText('录入已有成果').click()
  await expect(dialog.getByRole('radio', { name: '已完成' })).toBeChecked()

  const actualEnd = formItem(dialog, '实际完成日期').locator('input')
  await actualEnd.fill('2026-10-20')
  await actualEnd.press('Enter')
  await dialog.getByRole('button', { name: '保存', exact: true }).click()

  await expect(dialog).toBeHidden()
  expect(created).toHaveLength(1)
  expect(created[0]).toMatchObject({
    name: '旧版订单导出', historical: true, status: 'DONE',
    actualStart: null, actualEnd: '2026-10-20', planStart: null, planEnd: null
  })
})

test('保存并继续停留在弹窗，清空名称、保留位置且不重复提交', async ({ page }) => {
  const { state, created } = await setup(page)
  const dialog = await openNewDialog(page)
  const location = formItem(dialog, '所属位置').locator('.el-select')
  await location.click()
  await page.getByRole('option', { name: '订单与支付' }).click()
  await nameInput(dialog).fill('退款接口')
  await dialog.getByRole('button', { name: '保存并继续' }).click()

  await expect(page.getByText('工作项已创建，可继续新建下一项')).toBeVisible()
  await expect(dialog).toBeVisible()
  await expect(page).toHaveURL(new RegExp(`${WORK_ITEMS_URL}$`))
  await expect(nameInput(dialog)).toHaveValue('')
  await expect(location).toContainText('订单与支付')
  expect(created).toHaveLength(1)
  expect(created[0]).toMatchObject({ name: '退款接口', moduleId: 2 })

  await nameInput(dialog).fill('对账接口')
  await dialog.getByRole('button', { name: '保存并继续' }).click()
  await expect(nameInput(dialog)).toHaveValue('')
  expect(created.map(body => body.name)).toEqual(['退款接口', '对账接口'])
  expect(created[1]).toMatchObject({ moduleId: 2 })
  expect(state.writes).toEqual([])
})
