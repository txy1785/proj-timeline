import { test, expect } from '@playwright/test'
import { prepare } from './helpers/task-fixture.mjs'

test('delivered features retain blocked history without current risk badges in lists, timeline or my work', async ({ page }) => {
  await prepare(page, 'USER', 2, { closed:true, blocked:true })
  await page.goto('/projects/1/work-items')
  const row = page.getByRole('row').filter({ has:page.getByRole('button',{name:'订单接口开发',exact:true}) })
  await expect(row).toBeVisible()
  await expect(row.getByText('受阻',{exact:true})).toHaveCount(0)
  await page.goto('/projects/1/timeline')
  const bar=page.locator('.date-bar').filter({hasText:'10/19'})
  await expect(bar).toBeVisible()
  await expect(bar).not.toHaveAccessibleName(/受阻/)
  await expect(bar.locator('.bar-risk')).toHaveCount(0)
  const delivery={id:106,projectId:1,projectName:'商城系统开发',ownerId:2,ownerName:'李经理',name:'订单接口开发',status:'DONE',projectStatus:'ACTIVE',planStart:'2026-10-19',planEnd:'2026-10-26',flags:{blocked:true,overdue:false,near:false}}
  await page.route('**/api/workspace/**',route=>{
    const url=new URL(route.request().url())
    const data=url.pathname.endsWith('/management')?{nodes:[],requests:[],nodeTotal:0,requestTotal:0}:{list:url.searchParams.get('scope')==='DONE'?[delivery]:[],total:url.searchParams.get('scope')==='DONE'?1:0,page:1,size:20,businessDate:'2026-10-27',timeZone:'Asia/Shanghai',counts:{open:0,doing:0,overdue:0,done:1}}
    return route.fulfill({json:{code:0,msg:'ok',data}})
  })
  await page.route('**/api/projects?**',route=>route.fulfill({json:{code:0,msg:'ok',data:{list:[],total:0,page:1,size:100}}}))
  await page.goto('/work')
  await page.locator('.el-radio-button').filter({has:page.getByRole('radio',{name:'已交付',exact:true})}).click()
  await expect(page.locator('.personal-delivery-row')).toHaveCount(1)
  await expect(page.locator('.personal-delivery-row').getByText('受阻',{exact:true})).toHaveCount(0)
})
