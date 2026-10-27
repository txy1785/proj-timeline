

export async function prepare(page, role = 'USER', userId = 6, options = {}) {
  const state = {
    status: options.closed ? 'DONE' : 'IN_PROGRESS', projectStatus: options.archived ? 'ARCHIVED' : 'ACTIVE',
    failNextStatus: false, writes: [], notes: [], tasks: [
      { id: 11, nodeId: 106, name: '库存扣减接口', assigneeId: 6, assigneeName: '钱工', status: options.closed ? 'DONE' : 'DOING', dueDate: '2026-10-26', description: '已有说明', kind: 'BUG', relatedNodeId: 107, relatedNodeName: '原功能工作项', situation: '已有情况', handling: '已有处理方式', estimateDays: 2 },
      { id: 12, nodeId: 106, name: '接口联调', assigneeId: 4, assigneeName: '赵工', status: options.closed ? 'DONE' : 'TODO' },
      { id: 13, nodeId: 106, name: '接口说明', assigneeId: 4, assigneeName: '赵工', status: 'DONE' }
    ]
  }
  const members = [
    { id: 2, name: '李经理', username: 'pm', globalRole: 'USER', projectRole: 'PM', active: true },
    { id: 4, name: '赵工', username: 'owner', globalRole: 'USER', projectRole: 'MEMBER', active: true },
    { id: 6, name: '钱工', username: 'member', globalRole: role, projectRole: 'MEMBER', active: true }
  ]
  const node = () => ({
    id: 106, projectId: 1, projectStatus: state.projectStatus, moduleId: 2, moduleName: '订单与支付', moduleOwnerId: 4,
    name: '订单接口开发', type: 'FEATURE', ownerId: 4, ownerName: '赵工', status: state.status,
    planStart: '2026-10-19', planEnd: '2026-10-26', progress: Math.round(state.tasks.filter(task => task.status === 'DONE').length / state.tasks.length * 100),
    totalTasks: state.tasks.length, doneTasks: state.tasks.filter(task => task.status === 'DONE').length,
    deliverableTotal: 0, deliverableProvided: 0, deliverables: [], logs: [],
    flags: { overdue: !options.closed, near: false, blocked: false }, overdueReason: '联调环境延后',
    tasks: state.tasks.map(task => ({ ...task, noteCount: state.notes.length }))
  })
  const project = () => ({
    id: 1, name: '商城系统开发', code: 'MALL', planStart: '2026-10-05', planEnd: '2026-11-13', status: state.projectStatus, progress: 33,
    stats: { total: 1, done: 0, inProgress: 1, overdue: 1, near: 0, blocked: 0 },
    stageTargets: [], modules: [{ id: 2, projectId: 1, name: '订单与支付', ownerId: 4, ownerName: '赵工', nodes: [node()] }],
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
    if (method !== 'GET') state.writes.push({ method, path, body: req.postDataJSON() })
    if (path === '/auth/me') return reply({ id: userId, username: 'test', name: userId === 2 ? '李经理' : '钱工', globalRole: role, active: true })
    if (path === '/context') return reply({ businessDate: '2026-10-27', timeZone: 'Asia/Shanghai', unreadCount: 0 })
    if (path === '/nodes/106' && method === 'GET') return reply(node())
    if (path === '/projects/1/timeline') return reply(project())
    if (path === '/projects/1/members') return reply(members)
    if (path === '/tasks/11/notes' && method === 'GET') return reply(state.notes)
    if (path === '/tasks/11/notes' && method === 'POST') {
      const record = { id: 200 + state.notes.length, taskId: 11, authorId: userId, authorName: '钱工', content: req.postDataJSON().content, createdAt: '2026-10-27T09:30:00' }
      state.notes.unshift(record); return reply(record)
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
    if (path.startsWith('/attachments')) return reply([])
    return reply(null, 404, 'Unexpected mock endpoint: ' + method + ' ' + path)
  })
  return state
}
