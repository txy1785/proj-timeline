import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

async function prepareWorkspace(page) {
  const fixture=await prepare(page)
  const state={requests:[],deliveries:[{id:201,projectId:1,projectName:'商城系统开发',projectStatus:'ACTIVE',moduleName:'文件上传',name:'文件上传 COS',type:'FEATURE',ownerId:6,ownerName:'钱工',status:'IN_PROGRESS',planStart:'2026-10-19',planEnd:'2026-10-27',progress:0,flags:{overdue:false,near:true,blocked:false},openBugCount:1}],bugs:[{...fixture.tasks[0],projectId:1,projectName:'商城系统开发',nodeId:106,nodeName:'原功能工作项',nodeStatus:'DONE',originNodeId:107,originNodeName:'原功能工作项',originNodeStatus:'DONE',originOwnerId:4,originModuleOwnerId:4,projectStatus:'ACTIVE',status:'DOING',kind:'BUG',overdue:true}],failBug:false}
  const reply=(route,data,status=200,msg='ok')=>route.fulfill({status,json:{code:status===200?0:4090,msg,data}})
  function list(rows,url) {
    const selected=rows.filter(row=>url.searchParams.get('scope')==='HISTORY'||(url.searchParams.get('scope')==='DONE'?row.status==='DONE':row.status!=='DONE'))
    const page=Number(url.searchParams.get('page')||1),size=Number(url.searchParams.get('size')||20)
    return {list:selected.slice((page-1)*size,page*size),total:selected.length,page,size,businessDate:'2026-10-27',timeZone:'Asia/Shanghai',counts:{open:rows.filter(row=>row.status!=='DONE').length,doing:rows.filter(row=>['DOING','IN_PROGRESS'].includes(row.status)).length,overdue:0,done:rows.filter(row=>row.status==='DONE').length}}
  }
  await page.route('**/api/workspace/**',route=>{
    const url=new URL(route.request().url())
    state.requests.push({path:url.pathname,params:Object.fromEntries(url.searchParams)})
    if(url.pathname.endsWith('/management'))return reply(route,{nodes:[],requests:[],nodeTotal:0,requestTotal:0})
    if(url.pathname.endsWith('/deliveries'))return reply(route,list(state.deliveries,url))
    if(url.pathname.endsWith('/bugs'))return reply(route,list(state.bugs,url))
    return reply(route,list([{...fixture.tasks[1],projectId:1,projectName:'商城系统开发',projectStatus:'ACTIVE',nodeName:'历史工作项',nodeStatus:'IN_PROGRESS',overdue:false}],url))
  })
  await page.route('**/api/projects?**',route=>reply(route,{list:[],total:0,page:1,size:100}))
  await page.route('**/api/nodes/*/status',route=>{
    const body=route.request().postDataJSON(),id=Number(new URL(route.request().url()).pathname.split('/')[3])
    state.requests.push({path:new URL(route.request().url()).pathname,body})
    const node=state.deliveries.find(row=>row.id===id)
    expect(body.expectedStatus).toBe(node.status)
    node.status=body.status
    return reply(route,node)
  })
  await page.route('**/api/tasks/11/status',route=>{
    const body=route.request().postDataJSON()
    state.requests.push({path:'/api/tasks/11/status',body})
    if(state.failBug){state.failBug=false;state.bugs[0].status='DOING';return reply(route,null,409,'Bug 状态已变化，请刷新后重试')}
    expect(body.expectedStatus).toBe(state.bugs[0].status)
    state.bugs[0].status=body.status
    return reply(route,state.bugs[0])
  })
  await page.route('**/api/workload**',route=>reply(route,{weekStart:'2026-10-26',weekEnd:'2026-11-01',people:[{id:6,name:'钱工',deliveryOpenCount:1,deliveryDoingCount:1,deliveryOverdueCount:1,deliveryWeekDueCount:1,openBugCount:1,overdueBugCount:1}]}))
  return state
}

test('my work defaults to owned deliveries and requires explicit confirmation before delivery',async ({page})=>{
  const state=await prepareWorkspace(page)
  await page.goto('/work')
  await expect(page.getByRole('tab',{name:'我的工作项',exact:true})).toHaveAttribute('aria-selected','true')
  await expect(page.getByRole('link',{name:'文件上传 COS',exact:true})).toBeVisible()
  expect(state.requests.some(req=>req.path==='/api/workspace/tasks')).toBe(false)
  await page.locator('.personal-delivery-row').getByRole('button',{name:'确认交付',exact:true}).click()
  const confirm=page.getByRole('dialog',{name:'确认交付',exact:true})
  await expect(confirm.getByText(/仍有 1 个 Bug 待处理/)).toBeVisible()
  await confirm.getByRole('button',{name:'继续检查',exact:true}).click()
  await expect(confirm).toBeHidden()
  expect(state.deliveries[0].status).toBe('IN_PROGRESS')
  await page.locator('.personal-delivery-row').getByRole('button',{name:'确认交付',exact:true}).click()
  await confirm.getByRole('button',{name:'确认交付',exact:true}).click()
  await expect(page.getByRole('heading',{name:'当前没有你负责的待交付工作项',exact:true})).toBeVisible()
  expect(state.requests.find(req=>req.path==='/api/nodes/201/status').body).toEqual({status:'DONE',expectedStatus:'IN_PROGRESS'})
  await expect(confirm).toBeHidden()
  await page.locator('.el-radio-button').filter({has:page.getByRole('radio',{name:'已交付',exact:true})}).click()
  await expect(page.getByRole('link',{name:'文件上传 COS',exact:true})).toBeVisible()
})

test('an assigned Bug can be resolved after its source feature was delivered, while conflicts refresh once',async ({page})=>{
  const state=await prepareWorkspace(page)
  state.bugs[0].status='TODO';state.failBug=true
  await page.goto('/work')
  await page.getByRole('tab',{name:'我的 Bug',exact:true}).click()
  await expect(page.getByText('· 功能已交付',{exact:true})).toBeVisible()
  await expect(page.getByRole('link',{name:'原功能工作项',exact:true})).toHaveAttribute('href','/nodes/107')
  await page.getByRole('button',{name:'开始修复',exact:true}).click()
  await expect(page.getByText('Bug 状态已变化，请刷新后重试',{exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'标记已解决',exact:true})).toBeVisible()
  expect(state.requests.filter(req=>req.path==='/api/tasks/11/status')).toHaveLength(1)
  await page.getByRole('button',{name:'标记已解决',exact:true}).click()
  await expect(page.getByRole('heading',{name:'当前没有分配给你的待处理 Bug',exact:true})).toBeVisible()
  await page.getByRole('tab',{name:'历史任务',exact:true}).click()
  await expect(page.getByRole('button',{name:'接口联调',exact:true})).toBeVisible()
  expect(state.requests.find(req=>req.path==='/api/workspace/tasks').params).toMatchObject({scope:'HISTORY',legacyOnly:'true'})
  await expect(page.getByRole('button',{name:'库存扣减接口',exact:true})).toHaveCount(0)
})

test('workload counts drill into delivery and Bug lists using matching owner, week and overdue filters',async ({page})=>{
  const state=await prepareWorkspace(page)
  await page.goto('/workload')
  await expect(page.getByRole('heading',{name:'人员负载',exact:true})).toBeVisible()
  const row=page.getByRole('row').filter({has:page.getByRole('cell',{name:'钱工',exact:true})})
  await row.getByRole('button',{name:'1',exact:true}).nth(3).click()
  let dialog=page.getByRole('dialog',{name:'钱工 · 本周计划交付',exact:true})
  await expect(dialog.getByRole('link',{name:'文件上传 COS',exact:true})).toBeVisible()
  expect(state.requests.findLast(req=>req.path==='/api/workspace/deliveries').params).toMatchObject({ownerId:'6',scope:'OPEN',dueWeek:'2026-10-26'})
  await dialog.locator('.el-dialog__headerbtn').click()
  await row.getByRole('button',{name:'1',exact:true}).nth(5).click()
  dialog=page.getByRole('dialog',{name:'钱工 · 逾期 Bug',exact:true})
  await expect(dialog.getByRole('button',{name:'库存扣减接口',exact:true})).toBeVisible()
  expect(state.requests.findLast(req=>req.path==='/api/workspace/bugs').params).toMatchObject({assigneeId:'6',scope:'OPEN',overdue:'true'})
  await expect(page.getByText('全部未完成人天',{exact:true})).toHaveCount(0)
})
