export type GlobalRole = 'ADMIN' | 'USER' | 'VIEWER'
export type ProjectRole = 'PM' | 'MEMBER'
export type ProjectStatus = 'ACTIVE' | 'ARCHIVED'
export type NodeStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
export type TaskStatus = 'TODO' | 'DOING' | 'DONE'
export type ReportStatus = 'DRAFT' | 'PUBLISHED'

export interface ApiResponse<T> { code: number; msg: string; data: T }
export interface PageResponse<T> { list: T[]; total: number; page: number; size: number }
export interface User { id: number; username: string; name: string; email?: string; globalRole: GlobalRole; active: boolean; projectRole?: ProjectRole }
export interface ProjectStats { total: number; done: number; inProgress: number; overdue: number; blocked: number; near: number }
export interface Project { id: number; name: string; code: string; description?: string; planStart: string; planEnd?: string; status: ProjectStatus; progress: number; stats: ProjectStats }
export interface NodeFlags { overdue: boolean; near: boolean; blocked: boolean }
export interface NodeItem { id: number; projectId: number; moduleId: number; moduleName?: string; name: string; description?: string; planStart: string; planEnd: string; actualStart?: string; actualEnd?: string; status: NodeStatus; progress: number; ownerId: number; ownerName: string; flags: NodeFlags; tasks?: Task[] }
export interface ModuleItem { id: number; projectId: number; name: string; description?: string; ownerId: number; ownerName: string; progress?: number; nodeCount?: number; overdueCount?: number; nodes?: NodeItem[] }
export interface StageTarget { id: number; projectId: number; name: string; description?: string; targetDate: string; sortOrder?: number }
export interface Timeline extends Project { modules: ModuleItem[]; stageTargets: StageTarget[] }
export interface Task { id: number; nodeId: number; name: string; description?: string; assigneeId: number; assigneeName: string; status: TaskStatus; dueDate?: string; estimateDays?: number; actualDays?: number }
export interface WorkLog { id: number; taskId: number; userId: number; userName: string; workDate: string; days: number; note?: string }
export interface Notification { id: number; type: string; title: string; content?: string; link?: string; readAt?: string; createdAt: string }
export interface Report { id?: number; projectId: number; weekStart: string; weekEnd: string; note?: string; status: ReportStatus; content: Record<string, unknown> & { actualDays?: number; plannedDays?: number; completedNodes?: Record<string, unknown>[]; exceptionNodes?: Record<string, unknown>[] } }
