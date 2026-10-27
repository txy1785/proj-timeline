import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

test('a preserved note remains readable after the project becomes archived', async ({ page }) => {
  const state = await prepare(page)
  await page.goto('/nodes/106?task=11')
  await page.getByRole('tab', { name: /进展记录/ }).click()
  await page.getByRole('textbox', { name: '进展记录内容' }).fill('需要保留的草稿')
  state.projectStatus = 'ARCHIVED'
  await page.getByRole('button', { name: /标记完成|标记已解决/, exact: true }).click()
  await expect(page.getByRole('textbox', { name: '未保存的记录草稿' })).toHaveValue('需要保留的草稿')
  await expect(page.getByRole('button', { name: '追加记录', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: /关闭.*详情/ }).click()
  await expect(page.getByRole('button', { name: '保存并关闭', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '返回保留草稿', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '未保存的记录草稿' })).toHaveValue('需要保留的草稿')
})

test('confirming the last delivery on page two returns to the remaining first page', async ({ page }) => {
  await prepare(page)
  const nodes = Array.from({ length:21 },(_,i)=>({id:100+i,projectId:1,projectName:'商城系统开发',projectStatus:'ACTIVE',name:'交付工作项 '+i,type:'FEATURE',ownerId:6,ownerName:'钱工',status:'IN_PROGRESS',planStart:'2026-10-19',planEnd:'2026-10-27',progress:0,flags:{overdue:false,near:true,blocked:false}}))
  await page.route('**/api/workspace/**', route => {
    const url = new URL(route.request().url())
    const list = nodes.filter(node=>node.status!=='DONE')
    const current = Number(url.searchParams.get('page') || 1)
    const data = url.pathname.endsWith('/management') ? {nodes:[],requests:[],nodeTotal:0,requestTotal:0} : {
      list:list.slice((current-1)*20,current*20),total:list.length,page:current,size:20,
      businessDate:'2026-10-27',timeZone:'Asia/Shanghai',counts:{open:list.length,doing:list.length,overdue:0,done:0}
    }
    return route.fulfill({json:{code:0,msg:'ok',data}})
  })
  await page.route('**/api/nodes/*/status',route=>{
    const id=Number(new URL(route.request().url()).pathname.split('/')[3])
    const node=nodes.find(item=>item.id===id)
    const body=route.request().postDataJSON()
    expect(body.expectedStatus).toBe('IN_PROGRESS')
    node.status=body.status
    return route.fulfill({json:{code:0,msg:'ok',data:node}})
  })
  await page.route('**/api/projects?**',route=>route.fulfill({json:{code:0,msg:'ok',data:{list:[],total:0,page:1,size:100}}}))
  await page.goto('/work')
  await page.locator('.el-pagination .btn-next').click()
  await expect(page.locator('.personal-delivery-row')).toHaveCount(1)
  await page.locator('.personal-delivery-row .el-button').click()
  await page.getByRole('dialog',{name:'确认交付',exact:true}).getByRole('button',{name:'确认交付',exact:true}).click()
  await expect(page.locator('.personal-delivery-row')).toHaveCount(20)
  await expect(page.getByRole('link',{name:'交付工作项 0',exact:true})).toBeVisible()
  await expect(page.locator('.el-pagination')).toHaveCount(0)
})

test('weekly save blocks browser back until its original week has finished saving', async ({ page }) => {
  await prepare(page,'USER',2)
  let releaseSave
  let startedSave
  const started = new Promise(resolve=>{startedSave=resolve})
  const blocked = new Promise(resolve=>{releaseSave=resolve})
  const reports = new Map(['2026-10-19','2026-10-26'].map((week,i)=>[week,{id:50+i,projectId:1,weekStart:week,weekEnd:i?'2026-11-01':'2026-10-25',status:'DRAFT',note:'原有说明 '+week,content:{completedNodes:[],exceptionNodes:[],nextWeekDueNodes:[]}}]))
  await page.route('**/api/projects/1/reports**',route=>{
    const url = new URL(route.request().url())
    return route.fulfill({json:{code:0,msg:'ok',data:url.pathname.endsWith('/history')?[]:reports.get(url.searchParams.get('week')||'2026-10-26')}})
  })
  await page.route('**/api/reports/51/note',async route=>{
    startedSave()
    await blocked
    const value={...reports.get('2026-10-26'),note:route.request().postDataJSON().note}
    reports.set('2026-10-26',value)
    await route.fulfill({json:{code:0,msg:'ok',data:value}})
  })
  await page.goto('/projects/1/reports?week=2026-10-19')
  await expect(page.locator('textarea').first()).toHaveValue('原有说明 2026-10-19')
  const picker=page.getByRole('combobox',{name:'周报所在周'})
  await picker.fill('2026-10-26'); await picker.press('Enter')
  await expect(page).toHaveURL(/week=2026-10-26/)
  await expect(page.locator('textarea').first()).toHaveValue('原有说明 2026-10-26')
  await page.locator('textarea').first().fill('本周最终说明')
  await page.getByRole('button',{name:'保存说明',exact:true}).click()
  await started
  await page.evaluate(()=>history.back())
  // A cancelled popstate restores the original URL before the pending response returns.
  await expect(page).toHaveURL(/week=2026-10-26/)
  releaseSave()
  await expect(page.getByText('说明已保存，周报仍为草稿',{exact:true})).toBeVisible()
  await expect(page.locator('textarea').first()).toHaveValue('本周最终说明')
  await page.evaluate(()=>history.back())
  await expect(page).toHaveURL(/week=2026-10-19/)
  await expect(page.locator('textarea').first()).toHaveValue('原有说明 2026-10-19')
})
