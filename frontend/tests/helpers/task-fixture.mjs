export async function prepare(page, role = 'USER', userId = 6, options = {}) {
  const state = {
    status: options.closed ? 'DONE' : 'IN_PROGRESS', projectStatus: options.archived ? 'ARCHIVED' : 'ACTIVE',
    failNextStatus: false, writes: [], notes: [], extraModules: [], deliverables: [], files: [], tasks: [
      { id: 11, nodeId: 106, name: '库存扣减接口', assigneeId: 6, assigneeName: '钱工', status: options.closed ? 'DONE' : 'DOING', dueDate: '2026-10-26', description: '已有说明', kind: 'BUG', relatedNodeId: options.routedBug ? 107 : 106, relatedNodeName: options.routedBug ? '原功能工作项' : '订单接口开发', originNodeId: options.routedBug ? 107 : 106, originNodeName: options.routedBug ? '原功能工作项' : '订单接口开发', originNodeStatus: options.closed ? 'DONE' : 'IN_PROGRESS', originOwnerId: options.routedBug ? 6 : 4, originModuleOwnerId: options.routedBug ? 6 : 4, situation: '已有情况', handling: '已有处理方式', estimateDays: 2 },
      { id: 12, nodeId: 106, name: '接口联调', assigneeId: 4, assigneeName: '赵工', kind: 'FEATURE', status: options.closed ? 'DONE' : 'TODO' },
      { id: 13, nodeId: 106, name: '接口说明', assigneeId: 4, assigneeName: '赵工', kind: 'FEATURE', status: 'DONE' }
    ]
  }
  const members = [
    { id: 2, name: '李经理', username: 'pm', globalRole: 'USER', projectRole: 'PM', active: true },
    { id: 4, name: '赵工', username: 'owner', globalRole: 'USER', projectRole: 'MEMBER', active: true },
    { id: 6, name: '钱工', username: 'member', globalRole: role, projectRole: 'MEMBER', active: true }
  ]
  const taskView = task => ({ ...task, originNodeStatus: task.originNodeId === 106 ? state.status : task.originNodeStatus, noteCount: state.notes.length })
  const node = () => ({
    id: 106, projectId: 1, projectStatus: state.projectStatus, moduleId: 2, moduleName: '订单与支付', moduleOwnerId: 4,
    name: '订单接口开发', type: 'FEATURE', ownerId: 4, ownerName: '赵工', status: state.status,
    planStart: '2026-10-19', planEnd: '2026-10-26', progress: state.status === 'DONE' ? 100 : 0,
    totalTasks: state.tasks.length, doneTasks: state.tasks.filter(task => task.status === 'DONE').length,
    deliverableTotal: state.deliverables.length, deliverableProvided: state.deliverables.filter(item=>item.status==='PROVIDED').length, deliverables: state.deliverables, logs: [],
    bugs: state.tasks.filter(task=>task.kind==='BUG' && task.originNodeId===106).map(taskView), bugCount: state.tasks.filter(task=>task.kind==='BUG' && task.originNodeId===106).length, openBugCount: state.tasks.filter(task=>task.kind==='BUG' && task.status!=='DONE' && task.originNodeId===106).length, legacyTaskCount: state.tasks.filter(task=>task.kind!=='BUG').length,
    flags: { overdue: !options.closed, near: false, blocked: !!options.blocked }, overdueReason: '联调环境延后',
    tasks: state.tasks.map(taskView)
  })
  const project = () => ({
    id: 1, name: '商城系统开发', code: 'MALL', planStart: '2026-10-05', planEnd: '2026-11-13', status: state.projectStatus, progress: 33,
    stats: { total: 1, done: 0, inProgress: 1, overdue: 1, near: 0, blocked: 0 },
    stageTargets: [], modules: [{ id: 2, projectId: 1, name: '订单与支付', ownerId: 4, ownerName: '赵工', nodes: [node()] }, ...state.extraModules],
    projectWorkItems: [{ id: 107, projectId: 1, name: '原功能工作项', type: 'FEATURE', status: 'IN_PROGRESS', ownerId: 4, ownerName: '赵工', planStart: '2026-10-01', planEnd: '2026-10-20', progress: 0, flags: { overdue: false, blocked: false, near: false } }]
  })
  await page.addInitScript(({ role, userId }) => {
    sessionStorage.setItem('timeline-token', 'test-token')
    sessionStorage.setItem('timeline-user', JSON.stringify({ id: userId, username: 'test', name: userId === 2 ? '李经理' : '钱工', globalRole: role, active: true }))
  }, { role, userId })
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url())
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const path = url.pathname.slice(4), method = req.method()
    const reply = (data, status = 200, msg = 'ok') => route.fulfill({ status, json: { code: status === 200 ? 0 : 4090, msg, data } })
    if (method !== 'GET') {
      const json = String(req.headers()['content-type'] || '').includes('application/json')
      state.writes.push({ method, path, body: json ? req.postDataJSON() : null })
    }
    if (path === '/auth/me') return reply({ id: userId, username: 'test', name: userId === 2 ? '李经理' : '钱工', globalRole: role, active: true })
    if (path === '/context') return reply({ businessDate: '2026-10-27', timeZone: 'Asia/Shanghai', unreadCount: 0 })
    if (path === '/nodes/106' && method === 'GET') return reply(node())
    if (path === '/nodes/106/status' && method === 'POST') { state.status=req.postDataJSON().status; return reply(node()) }
    if (path === '/projects/1/bug-routing') return reply({canSubmit:state.projectStatus==='ACTIVE'&&state.status!=='CANCELLED',nodeId:106,nodeName:'订单接口开发',ownerId:4,ownerName:'赵工',message:'已确认关联功能'})
    if (path === '/projects/1/bugs' && method === 'POST') { const task={id:14,nodeId:106,kind:'BUG',relatedNodeId:106,originNodeId:106,originNodeName:'订单接口开发',originNodeStatus:state.status,originOwnerId:4,originModuleOwnerId:4,assigneeId:4,assigneeName:'赵工',status:'TODO',...req.postDataJSON()}; state.tasks.push(task); return reply({...task,routedNodeId:106,routedNodeName:'订单接口开发'}) }
    if (path === '/projects/1/timeline') return reply(project())
    if (path === '/projects/1/members') return reply(members)
    if (/^\/tasks\/\d+\/notes$/.test(path) && method === 'GET') return reply(path==='/tasks/11/notes'?state.notes:[])
    if (path === '/tasks/11/notes' && method === 'POST') {
      const body = req.postDataJSON()
      const record = { id: 200 + state.notes.length, taskId: 11, title: body.title || '进展记录.md', authorId: userId, authorName: '钱工', content: body.content, createdAt: '2026-10-27T09:30:00' }
      state.notes.unshift(record); return reply(record)
    }
    if (/^\/task-notes\/\d+$/.test(path) && method === 'PUT') {
      const id = Number(path.split('/').pop())
      const record = state.notes.find(item => item.id === id)
      if (!record) return reply(null, 404, '记录不存在')
      const body = req.postDataJSON()
      record.title = body.title
      record.content = body.content
      record.updatedBy = userId
      record.updatedByName = '钱工'
      record.updatedAt = '2026-10-27T10:30:00'
      return reply(record)
    }
    if (path === '/tasks/11/status' && method === 'PUT') {
      if (state.failNextStatus) { state.failNextStatus = false; state.tasks[0].status = 'TODO'; return reply(null, 409, '任务状态已变化，请刷新后重试') }
      state.tasks[0].status = req.postDataJSON().status; return reply(state.tasks[0])
    }
    if (path === '/tasks/11' && method === 'PUT') { Object.assign(state.tasks[0], req.postDataJSON()); return reply(state.tasks[0]) }
    if (path === '/nodes/106/tasks' && method === 'POST') {
      const task = { id: 14, nodeId: 106, ...req.postDataJSON(), status: 'TODO', assigneeName: '赵工' }
      state.tasks.push(task); return reply(task)
    }
    if (path === '/attachments/categories') return reply([{ownerType:'NODE',group:'功能资料',code:'NODE_DESIGN',name:'功能设计说明'},{ownerType:'TASK',group:'问题资料',code:'TASK_REFERENCE',name:'参考资料'},{ownerType:'TASK',group:'问题资料',code:'TASK_RECORD',name:'进展记录附件'}])
    if (/^\/attachments\/\d+\/content$/.test(path) && method === 'GET') {
      // 240×120 PNG，用于验证记录正文里的图片真正内联渲染
      const png = 'iVBORw0KGgoAAAANSUhEUgAAAPAAAAB4CAYAAADMtn8nAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAARgSURBVHhe7dNdrls3DEXhzKhD68g7hhZ5MCAskPqx5V7veBH4gIiieAzk7l9//f3Pv5Iy/WJDUg4DLAUzwFIwAywFM8BSMAMsBTPAUjADLAUzwFIwAywFM8BSMAMsBTPAUjADLAUzwFIwAywFM8BSMAMsBTPAUjADLAUzwFIwAywFM8BSMAMsBTPAUjADLAUzwFIwAywFM8BSMAMsBTPAUjADLAUzwFIwAywFM8BSMAMsBTPAUjADLAUzwFIwAywFM8BSMAMsBTPAUjADLAUzwFIwAywFM8BSMAP8Q34Xe9Kprw3wKkCPYr9zOnsyL3UiA/xqAHbez2bYH4uzndP56u0rO+gdxW/ovq8LcPUHVu3iDN/O7rirszO7U7tzYz3zndPiN3Tfjwd4t6o33LWDb8fanRv71ZvH/as12zH79k5179kf79nrrHbpnq8KcPeu6nfFtzTOvFrcPe7c7VM31/VXd5XTeT3vxwNM1X/+s1Xt4Pe4v+qN/fGevR2znTu6t12furlVf6f4hrt031cE+KT4hr+vup/NVW+q+apXmb2v+tTNVf3T4jt+Q/dFBJhWM9V9Vd19t6ObfxRnqnn2d+9Xc6fVve92cb56N+vpPf64AI/Fu5XxzWzHWNV5NjtTze3U7txYq2/z3ztvVn3dFxtg9vieM7t1Mt/tr3qndbJjnBt/U6Wb6/rV/cms3ssAo07eVN+uervF+dku3r1qZ29X3Rz7uu/rAsz53fv/U/c7xuLcM9XtntVqdrzn79d9Bri555lmd8Rdq3Onq9ndrKrd3MXfUOEsz3qfuAA/e79bnOce7mKfs9Wu1bnCYo/zu3t3Zlm8p905ve7jA7w6U3e/W5yf7ei+seqvzpXHPWd55pvujmazq+rm2dd9HxXgWY33fFft2O139+OZxbfVm1l/dabxvpt9pqr33Fthdffs674fD/Cqqlnu2Jnp+t19VXwzez+7W50fveqOZ/ZPqnrP81j8Zud0Xs/7qADzrppjn7q53ermq308s8c97HVnvhvveJ55Zfa0Zrv0Pj8e4B0nfxDd7G5xnnu6e55nffZ47nqzfuXm7KqqWe7QfR8dYBbvK91s1+/ueSbe8zz2un537nqzfuVds3xX9aq+7vvYALNW96xunv3unmca78fi/djjHc8svqvezt7v1I0dXfG3676PDfBvsz+CWXF2nOeZtbofi7M8j73Zb+GbR/HdydvdurGjK/523ffRAb6Jf1RVdW+64hzfct8N1bduuLn35i7NfU2ApT+RAZaCGWApmAGWghlgKZgBloIZYCmYAZaCGWApmAGWghlgKZgBloIZYCmYAZaCGWApmAGWghlgKZgBloIZYCmYAZaCGWApmAGWghlgKZgBloIZYCmYAZaCGWApmAGWghlgKZgBloIZYCmYAZaCGWApmAGWghlgKZgBloIZYCmYAZaCGWApmAGWghlgKZgBloIZYCmYAZaCGWApmAGWghlgKZgBloL9B1WdL+PyHL0IAAAAAElFTkSuQmCC'
      return route.fulfill({ status: 200, headers: { 'Content-Type': 'image/png' }, body: Buffer.from(png, 'base64') })
    }
    if (path === '/attachments' && method === 'POST') {
      // 只解析 multipart 里的文件名，足够覆盖「粘贴上传 → 插入占位 → 附件区可见」的链路
      const raw = req.postDataBuffer()?.toString('binary') || ''
      const match = raw.match(/filename="([^"]*)"/)
      const originalName = match ? match[1] : 'attachment.png'
      const category = (raw.match(/name="category"\r\n\r\n([^\r\n]+)/) || [])[1] || 'TASK_OTHER'
      if (state.uploadDelayMs) await new Promise(resolve => setTimeout(resolve, state.uploadDelayMs))
      const file = { id: 900 + state.files.length, projectId: 1, ownerType: 'TASK', ownerId: 11, category, categoryName: '进展记录附件', title: originalName, originalName, contentType: 'image/png', sizeBytes: 4, version: 1, uploaderId: userId, uploaderName: '钱工', createdAt: '2026-10-27T09:30:00' }
      state.files.push(file); return reply(file)
    }
    if (path === '/attachments') return reply(state.files.filter(file=>file.ownerType===url.searchParams.get('ownerType')))
    if (path.startsWith('/attachments')) return reply([])
    return reply(null, 404, 'Unexpected mock endpoint: ' + method + ' ' + path)
  })
  state.project = project
  return state
}
