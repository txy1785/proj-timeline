import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

/** 工作项记录接口的内存实现；附件上传沿用共享夹具，归属按请求里的 ownerType 记录。 */
async function mockNodeNotes(page, state, userId) {
  state.nodeNotes = []
  state.nodeUploads = []
  await page.route(/\/api\/(nodes\/106\/notes|node-notes\/\d+)$/, async route => {
    const req = route.request(), path = new URL(req.url()).pathname.slice(4), method = req.method()
    const reply = (data, status = 200) => route.fulfill({ status, json: { code: status === 200 ? 0 : 4004, msg: 'ok', data } })
    if (method !== 'GET') state.writes.push({ method, path, body: method === 'DELETE' ? null : req.postDataJSON() })
    if (path === '/nodes/106/notes' && method === 'GET') return reply(state.nodeNotes)
    if (path === '/nodes/106/notes' && method === 'POST') {
      const body = req.postDataJSON()
      const note = { id: 500 + state.nodeNotes.length, nodeId: 106, title: body.title, content: body.content, authorId: userId, authorName: '李经理', createdAt: '2026-10-27T09:30:00' }
      state.nodeNotes.unshift(note); return reply(note)
    }
    const note = state.nodeNotes.find(item => item.id === Number(path.split('/').pop()))
    if (!note) return reply(null, 404)
    if (method === 'PUT') { Object.assign(note, req.postDataJSON(), { updatedAt: '2026-10-27T10:00:00', updatedByName: '李经理' }); return reply(note) }
    state.nodeNotes = state.nodeNotes.filter(item => item !== note); return reply(null)
  })
  await page.route('**/api/attachments', async route => {
    if (route.request().method() !== 'POST') return route.fallback()
    const raw = route.request().postDataBuffer()?.toString('binary') || ''
    state.nodeUploads.push({ ownerType: (raw.match(/name="ownerType"\r\n\r\n([^\r\n]+)/) || [])[1], category: (raw.match(/name="category"\r\n\r\n([^\r\n]+)/) || [])[1] })
    return route.fallback()
  })
}

async function pasteImage(page) {
  await page.evaluate(() => {
    const data = new DataTransfer()
    data.items.add(new File([new Uint8Array([137, 80, 78, 71])], '流程图.png', { type: 'image/png' }))
    const event = new ClipboardEvent('paste', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'clipboardData', { value: data })
    document.querySelector('[role="textbox"][aria-label="进展记录内容"]').dispatchEvent(event)
  })
}

test('work item manager writes, edits and deletes named Markdown records with pasted files', async ({ page }) => {
  test.setTimeout(30_000)
  const state = await prepare(page, 'USER', 2)
  await mockNodeNotes(page, state, 2)
  await page.goto('/nodes/106')
  await page.getByRole('tab', { name: /^进展记录/ }).click()
  const name = page.getByRole('textbox', { name: '进展记录名称' })
  await expect(name).toHaveValue('进展记录-2026-10-27-1.md')
  await name.fill('接口设计')
  const draft = page.getByRole('textbox', { name: '进展记录内容' })
  await draft.click()
  await draft.pressSequentially('## 订单接口')
  await pasteImage(page)
  await expect(draft.locator('img')).toHaveCount(1)
  expect(state.nodeUploads).toContainEqual({ ownerType: 'NODE', category: 'NODE_RECORD' })
  await page.getByRole('button', { name: '保存记录', exact: true }).click()
  await expect(page.getByText('📄 接口设计.md')).toBeVisible()
  const saved = state.writes.find(item => item.method === 'POST' && item.path === '/nodes/106/notes')
  expect(saved.body.title).toBe('接口设计.md')
  expect(saved.body.content).toMatch(/订单接口[\s\S]*attachment:900/)
  await expect(page.getByRole('tab', { name: '进展记录 1', exact: true })).toBeVisible()

  await page.locator('.task-note').getByRole('button', { name: '编辑', exact: true }).click()
  await expect(page.getByText('编辑已保存记录', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '源码', exact: true }).click()
  await page.getByRole('textbox', { name: '进展记录内容' }).fill('改为联调结论')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.locator('.task-note')).toContainText('改为联调结论')
  expect(state.writes.find(item => item.method === 'PUT' && item.path === '/node-notes/500').body.content).toBe('改为联调结论')

  await page.locator('.task-note').getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('dialog', { name: '删除记录' }).getByRole('button', { name: '删除', exact: true }).click()
  await expect(page.locator('.task-note')).toHaveCount(0)
})

test('unsaved work item record blocks leaving until the user decides', async ({ page }) => {
  const state = await prepare(page, 'USER', 2)
  await mockNodeNotes(page, state, 2)
  await page.goto('/nodes/106?tab=records')
  const draft = page.getByRole('textbox', { name: '进展记录内容' })
  await draft.click()
  await draft.pressSequentially('还没保存')
  await page.getByRole('navigation', { name: '当前项目' }).getByRole('link', { name: '概览', exact: true }).click()
  const prompt = page.getByRole('dialog', { name: '未保存的进展记录' })
  await expect(prompt).toBeVisible()
  await prompt.getByRole('button', { name: '放弃并关闭', exact: true }).click()
  await expect(page).toHaveURL('/projects/1/overview')
})

test('members without work item rights read records but cannot write them', async ({ page }) => {
  const state = await prepare(page, 'USER', 6)
  await mockNodeNotes(page, state, 6)
  state.nodeNotes.push({ id: 700, nodeId: 106, title: '验收说明.md', content: '已验收', authorId: 4, authorName: '赵工', createdAt: '2026-10-26T09:00:00' })
  await page.goto('/nodes/106?tab=records')
  await expect(page.getByText('📄 验收说明.md')).toBeVisible()
  await expect(page.getByRole('textbox', { name: '进展记录内容' })).toHaveCount(0)
  await expect(page.locator('.task-note').getByRole('button', { name: '编辑', exact: true })).toHaveCount(0)
  await expect(page.locator('.task-note').getByRole('button', { name: '下载 .md', exact: true })).toBeVisible()
})