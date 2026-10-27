import type { ModuleItem, NodeItem, Task, User } from '../api/types'

export function isProjectManager(user: User | null | undefined, members: User[]): boolean {
  return !!user && user.active !== false && user.globalRole !== 'VIEWER'
    && (user.globalRole === 'ADMIN' || members.some(member => member.id === user.id && member.projectRole === 'PM'))
}

export function isWorkItemManager(user: User | null | undefined, members: User[], node?: NodeItem | null): boolean {
  return !!user && !!node && user.active !== false && user.globalRole !== 'VIEWER'
    && (isProjectManager(user, members) || node.ownerId === user.id || node.moduleOwnerId === user.id)
}

export function canManageNode(user: User | null | undefined, members: User[], node?: NodeItem | null): boolean {
  return node?.projectStatus === 'ACTIVE' && isWorkItemManager(user, members, node)
}

export function canManageTask(user: User | null | undefined, members: User[], node: NodeItem | null | undefined, task: Task): boolean {
  return !!user && user.active !== false && user.globalRole !== 'VIEWER' && node?.projectStatus === 'ACTIVE'
    && (isWorkItemManager(user, members, node) || task.assigneeId === user.id)
}

export function canEditModule(user: User | null | undefined, members: User[], module: ModuleItem): boolean {
  return !!user && user.active !== false && user.globalRole !== 'VIEWER'
    && (isProjectManager(user, members) || module.ownerId === user.id)
}

export function isNodeClosed(node: NodeItem | null | undefined): boolean {
  return node?.status === 'DONE' || node?.status === 'CANCELLED'
}
