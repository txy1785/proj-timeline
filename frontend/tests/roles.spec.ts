import { expect, test, type Page } from '@playwright/test'

type Role = 'ADMIN' | 'USER' | 'VIEWER'

async function signIn(page: Page, name: string, role: Role) {
  await page.addInitScript(({ name, role }) => {
    sessionStorage.setItem('timeline-token', 'test-token')
    sessionStorage.setItem('timeline-user', JSON.stringify({ id: 1, username: name, name, globalRole: role, active: true }))
  }, { name, role })
  await page.route('**/api/projects**', route => route.fulfill({ json: { code: 0, msg: 'ok', data: { list: [], total: 0, page: 1, size: 100 } } }))
  await page.route('**/api/auth/me', route => route.fulfill({ json: { code:0, msg:'ok', data:{id:1, username:name, name, globalRole:role, active:true} } }))
  await page.route('**/api/context', route => route.fulfill({ json:{code:0,msg:'ok',data:{businessDate:'2026-10-27',timeZone:'Asia/Shanghai',unreadCount:0}} }))
}

test('anonymous users are redirected to login', async ({ page }) => {
  await page.goto('/projects')
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
})

for (const persona of [{ name: 'Project Manager', role: 'USER' }, { name: 'Team Member', role: 'USER' }, { name: 'Read Only', role: 'VIEWER' }] as const) {
  test(`${persona.name} can enter the project overview`, async ({ page }) => {
    await signIn(page, persona.name, persona.role)
    await page.goto('/projects')
    await expect(page.getByRole('heading', { name: '项目总览' })).toBeVisible()
    await expect(page.getByText(persona.name)).toBeVisible()
    if (persona.role === 'VIEWER') await expect(page.getByRole('button', { name: '新建项目' })).toHaveCount(0)
    else await expect(page.getByRole('button', { name: '新建项目' })).toBeVisible()
  })
}

test('VIEWER cannot enter administrator routes', async ({ page }) => {
  await signIn(page, 'Read Only', 'VIEWER')
  await page.goto('/admin/users')
  await expect(page).toHaveURL(/\/projects$/)
  await expect(page.getByRole('heading', { name: '项目总览' })).toBeVisible()
})

test('ADMIN can enter account management', async ({ page }) => {
  await signIn(page, 'Administrator', 'ADMIN')
  await page.route('**/api/users**', route => route.fulfill({ json: { code: 0, msg: 'ok', data: { list: [], total: 0, page: 1, size: 100 } } }))
  await page.goto('/admin/users')
  await expect(page.getByRole('heading', { name: '账号管理' })).toBeVisible()
})
