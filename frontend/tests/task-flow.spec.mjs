import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

async function openBug(page) {
  await page.getByRole('tab', { name: /^Bug / }).click()
  await page.getByRole('button', { name: '库存扣减接口', exact: true }).click()
  await expect(page.getByRole('heading', { name: '库存扣减接口', exact: true })).toBeVisible()
}

test('assignee opens Bug, keeps note draft across 409 and saves it independently', async ({ page }) => {
  const state=await prepare(page)
  await page.goto('/nodes/106')
  await expect(page.getByRole('tab',{name:'功能说明',exact:true})).toHaveAttribute('aria-selected','true')
  await expect(page.getByRole('button',{name:'新建任务',exact:true})).toHaveCount(0)
  await expect(page.getByText('暂无任务 · 0%',{exact:true})).toHaveCount(0)
  await expect(page.getByRole('button',{name:'确认交付',exact:true})).toHaveCount(0)
  await openBug(page)
  await expect(page.getByRole('button',{name:'编辑Bug',exact:true})).toHaveCount(0)
  await page.getByRole('tab',{name:/进展记录/}).click()
  const draft=page.getByRole('textbox',{name:'进展记录内容'})
  await draft.fill('本次未保存的进展')
  state.failNextStatus=true
  await page.locator('.el-drawer').getByRole('button',{name:'标记已解决',exact:true}).click()
  await expect(page.locator('.el-drawer').getByRole('button',{name:'开始修复',exact:true})).toBeVisible()
  await expect(draft).toHaveValue('本次未保存的进展')
  const write=state.writes.find(item=>item.path==='/tasks/11/status')
  expect(write.body).toEqual({status:'DONE',expectedStatus:'DOING'})
  await page.getByRole('button',{name:'追加记录',exact:true}).click()
  await expect(draft).toHaveValue('')
  await expect(page.getByText('本次未保存的进展',{exact:true})).toBeVisible()
})

test('PM edits Bug core fields while fixed type, origin and collapsed legacy values are preserved', async ({ page }) => {
  const state=await prepare(page,'USER',2)
  await page.goto('/nodes/106?task=11')
  await page.getByRole('button',{name:'编辑Bug',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'编辑Bug',exact:true})
  await expect(dialog.getByLabel('问题背景',{exact:true})).toBeHidden()
  await dialog.getByLabel('Bug标题',{exact:true}).fill('库存扣减接口修复')
  await dialog.getByRole('button',{name:'保存Bug',exact:true}).click()
  await expect(dialog).toBeHidden()
  const edited=state.writes.find(item=>item.path==='/tasks/11').body
  expect(edited).toMatchObject({description:'已有说明',kind:'BUG',relatedNodeId:106,situation:'已有情况',handling:'已有处理方式',estimateDays:2})
})

test('delivered function keeps Bug editable while closed legacy tasks remain readable', async ({ page }) => {
  const state=await prepare(page,'USER',6,{closed:true})
  await page.goto('/nodes/106?task=11')
  await page.locator('.el-drawer').getByRole('button',{name:'重新打开',exact:true}).click()
  await expect(page.locator('.el-drawer').getByRole('button',{name:'开始修复',exact:true})).toBeVisible()
  expect(state.status).toBe('DONE')
  expect(state.tasks[0].status).toBe('TODO')
  await page.getByRole('button',{name:'关闭Bug详情'}).click()
  await page.goto('/nodes/106/kanban')
  await expect(page.getByRole('tab',{name:/历史任务/})).toHaveAttribute('aria-selected','true')
  await page.getByRole('button',{name:'接口联调',exact:true}).click()
  await expect(page.getByRole('heading',{name:'接口联调',exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'重新开始',exact:true})).toHaveCount(0)
  await expect(page.getByRole('button',{name:'编辑任务',exact:true})).toHaveCount(0)
})

test('archived Bug and VIEWER expose no mutation actions', async ({ page }) => {
  await prepare(page,'VIEWER',6,{archived:true})
  await page.goto('/nodes/106?task=11')
  await expect(page.getByRole('heading',{name:'库存扣减接口',exact:true})).toBeVisible()
  await expect(page.locator('.el-drawer').getByRole('button',{name:'标记已解决',exact:true})).toHaveCount(0)
  await expect(page.getByRole('button',{name:'编辑Bug',exact:true})).toHaveCount(0)
  await expect(page.getByRole('button',{name:'报告 Bug',exact:true})).toHaveCount(0)
  await page.getByRole('tab',{name:/进展记录/}).click()
  await expect(page.getByRole('textbox',{name:'进展记录内容'})).toHaveCount(0)
  await expect(page.getByRole('button',{name:'追加记录',exact:true})).toHaveCount(0)
})

test('Bug draft prompts before close and invalid deep link has explicit unavailable state', async ({ page }) => {
  await prepare(page)
  await page.goto('/nodes/106?task=11')
  await page.getByRole('tab',{name:/进展记录/}).click()
  await page.getByRole('textbox',{name:'进展记录内容'}).fill('待补充的处理结果')
  await page.getByRole('button',{name:'关闭Bug详情'}).click()
  await expect(page.getByRole('button',{name:'保存并关闭',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'放弃并关闭',exact:true}).click()
  await expect(page).not.toHaveURL(/task=/)
  await page.goto('/nodes/106?task=999')
  await expect(page.getByText('任务已删除或不属于这个工作项',{exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'追加记录',exact:true})).toHaveCount(0)
})

test('mobile Bug rows retain a full-width detail entry', async ({ page }) => {
  await page.setViewportSize({width:390,height:844})
  await prepare(page)
  await page.goto('/nodes/106')
  await openBug(page)
  await expect.poll(async()=>Math.round((await page.locator('.el-drawer').boundingBox()).x)).toBe(0)
})

test('PM confirms delivery despite unfinished legacy tasks and sends expected work-item state', async ({ page }) => {
  const state=await prepare(page,'USER',2)
  await page.goto('/nodes/106')
  const confirm=page.getByRole('button',{name:'确认交付',exact:true})
  await expect(confirm).toBeEnabled()
  await confirm.click()
  await page.getByRole('button',{name:'确认已交付',exact:true}).click()
  await expect(page.getByText('功能已交付，后续问题可继续报告和修复，无需重开功能。',{exact:true})).toBeVisible()
  expect(state.status).toBe('DONE')
  expect(state.tasks[1].status).toBe('TODO')
  expect(state.writes.find(item=>item.path==='/nodes/106/status').body).toEqual({status:'DONE',expectedStatus:'IN_PROGRESS'})
})

test('members report a Bug directly against a delivered function with no repair work-item prerequisite', async ({ page }) => {
  const state=await prepare(page,'USER',6,{closed:true})
  await page.goto('/nodes/106')
  await page.locator('.node-page-head').getByRole('button',{name:'报告 Bug',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'报告 Bug',exact:true})
  await expect(dialog.getByRole('textbox',{name:'关联功能'})).toHaveValue('订单接口开发')
  await dialog.getByLabel('Bug标题',{exact:true}).fill('交付后发现上传失败')
  await dialog.getByRole('button',{name:'报告Bug',exact:true}).click()
  await expect(page.getByRole('heading',{name:'Bug已报告',exact:true})).toBeVisible()
  expect(state.writes.find(item=>item.path==='/projects/1/bugs').body.relatedNodeId).toBe(106)
  expect(state.status).toBe('DONE')
})

test('delivery requirements and actual files share the materials tab', async ({ page }) => {
  const state=await prepare(page,'USER',2)
  state.deliverables.push({id:1,nodeId:106,projectId:1,name:'接口设计说明',status:'PROVIDED',attachmentId:50,attachmentTitle:'接口文档'})
  state.files.push({id:50,projectId:1,ownerType:'NODE',ownerId:106,category:'NODE_DESIGN',categoryName:'功能设计说明',title:'接口文档',originalName:'api.txt',sizeBytes:50,version:1,uploaderId:4,createdAt:'2026-10-27T09:00:00'})
  await page.goto('/nodes/106')
  await page.getByRole('tab',{name:'交付资料',exact:true}).click()
  await expect(page.getByRole('heading',{name:'交付要求',exact:true})).toBeVisible()
  await expect(page.getByText('接口设计说明',{exact:true})).toBeVisible()
  await expect(page.getByRole('heading',{name:'图纸、文档与实际文件',exact:true})).toBeVisible()
  await expect(page.getByText('api.txt',{exact:true})).toBeVisible()
  await expect(page.getByRole('tab',{name:'文件',exact:true})).toHaveCount(0)
})

test('legacy routed Bug uses its source function manager for editing capability', async ({ page }) => {
  await prepare(page,'USER',6,{routedBug:true})
  await page.goto('/nodes/106?task=11')
  await expect(page.getByRole('button',{name:'编辑Bug',exact:true})).toBeVisible()
  await expect(page.locator('.drawer-parent').getByRole('button',{name:'原功能工作项 →',exact:true})).toBeVisible()
})


test('cancelled function permits history reading but blocks new Bug and status/content changes', async ({ page }) => {
  const state=await prepare(page,'USER',2)
  state.status='CANCELLED'
  await page.goto('/nodes/106?task=11')
  await expect(page.getByRole('heading',{name:'库存扣减接口',exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'报告 Bug',exact:true})).toHaveCount(0)
  await expect(page.locator('.el-drawer').getByRole('button',{name:'标记已解决',exact:true})).toHaveCount(0)
  await expect(page.locator('.el-drawer').getByRole('button',{name:'编辑Bug',exact:true})).toHaveCount(0)
})

test('a failed Bug report preserves its title and description', async ({ page }) => {
  await prepare(page,'USER',6,{closed:true})
  await page.route('**/api/projects/1/bugs',route=>route.fulfill({status:409,json:{code:4090,msg:'负责人已变化，请重试',data:null}}))
  await page.goto('/nodes/106')
  await page.locator('.node-page-head').getByRole('button',{name:'报告 Bug',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'报告 Bug',exact:true})
  await dialog.getByLabel('Bug标题',{exact:true}).fill('仍需保留的Bug')
  await dialog.getByLabel('问题描述（可选）',{exact:true}).fill('完整复现步骤')
  await dialog.getByRole('button',{name:'报告Bug',exact:true}).click()
  await expect(page.getByText('负责人已变化，请重试',{exact:true})).toBeVisible()
  await expect(dialog.getByLabel('Bug标题',{exact:true})).toHaveValue('仍需保留的Bug')
  await expect(dialog.getByLabel('问题描述（可选）',{exact:true})).toHaveValue('完整复现步骤')
})
