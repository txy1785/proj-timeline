export type GlobalRole = 'ADMIN' | 'USER' | 'VIEWER'
export type ProjectRole = 'PM' | 'MEMBER'
export type ProjectStatus = 'ACTIVE' | 'ARCHIVED'
export type NodeStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
export type TaskStatus = 'TODO' | 'DOING' | 'DONE'
export type DeliverableStatus = 'WANTED' | 'PROVIDED'
export type ChangeRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
export type ReportStatus = 'DRAFT' | 'PUBLISHED'
export type AttachmentOwnerType = 'PROJECT' | 'MODULE' | 'NODE' | 'TASK'

export interface ApiResponse<T> { code: number; msg: string; data: T }
export interface PageResponse<T> { list: T[]; total: number; page: number; size: number }
export interface User { id: number; username: string; name: string; email?: string; globalRole: GlobalRole; active: boolean; projectRole?: ProjectRole }
export interface ProjectStats { total: number; done: number; inProgress: number; overdue: number; blocked: number; near: number }
export interface Project { id: number; name: string; code: string; description?: string; planStart: string; planEnd?: string; status: ProjectStatus; progress: number; stats: ProjectStats }
export interface NodeFlags { overdue: boolean; near: boolean; blocked: boolean }
export interface NodeItem { id: number; projectId: number; moduleId: number; moduleName?: string; moduleOwnerId?: number; projectStatus?: ProjectStatus; name: string; description?: string; planStart: string; planEnd: string; actualStart?: string; actualEnd?: string; status: NodeStatus; progress: number; totalTasks?: number; doneTasks?: number; deliverableTotal?: number; deliverableProvided?: number; ownerId: number; ownerName: string; flags: NodeFlags; tasks?: Task[]; deliverables?: Deliverable[] }
export interface ModuleItem { id: number; projectId: number; name: string; description?: string; ownerId: number; ownerName: string; progress?: number; nodeCount?: number; overdueCount?: number; nodes?: NodeItem[] }
export interface StageTarget { id: number; projectId: number; name: string; description?: string; targetDate: string; sortOrder?: number; metricName?: string; metricUnit?: string; metricTarget?: number; metricCurrent?: number; progress?: number | null }
/** 模块变更申请：没有模块编辑权限的成员提交，PM 审批通过后自动改模块。 */
export interface ChangeRequest { id: number; projectId: number; moduleId: number; moduleName?: string; moduleOwnerId?: number; requestedBy: number; requesterName?: string; name: string; description?: string; ownerId: number; ownerName?: string; reason?: string; status: ChangeRequestStatus; decidedBy?: number; deciderName?: string; decidedAt?: string; decisionRemark?: string; createdAt: string }
export interface Timeline extends Project { modules: ModuleItem[]; stageTargets: StageTarget[] }
export interface Task { id: number; nodeId: number; name: string; description?: string; situation?: string; handling?: string; assigneeId: number; assigneeName: string; status: TaskStatus; dueDate?: string; estimateDays?: number; actualDays?: number; noteCount?: number }
export interface TaskNote { id: number; taskId: number; authorId: number; authorName: string; content: string; createdAt: string }
/** 节点交付物清单项：文件复用节点附件，attachmentId 可选。 */
export interface Deliverable { id: number; nodeId: number; projectId: number; name: string; status: DeliverableStatus; ownerId?: number; ownerName?: string; remark?: string; attachmentId?: number; attachmentTitle?: string; attachmentOriginalName?: string; attachmentDeleted?: number; sortOrder?: number; createdAt?: string; updatedAt?: string }
export interface WorkLog { id: number; taskId: number; userId: number; userName: string; workDate: string; days: number; note?: string }
export interface Notification { id: number; type: string; title: string; content?: string; link?: string; readAt?: string; createdAt: string }
export interface Report { id?: number; projectId: number; weekStart: string; weekEnd: string; note?: string; status: ReportStatus; content: Record<string, unknown> & { actualDays?: number; plannedDays?: number; completedNodes?: Record<string, unknown>[]; exceptionNodes?: Record<string, unknown>[] } }
export interface AttachmentCategory { ownerType: AttachmentOwnerType; group: string; code: string; name: string }
export interface Attachment { id: number; projectId: number; ownerType: AttachmentOwnerType; ownerId: number; ownerName?: string; category: string; categoryName?: string; title: string; originalName: string; contentType?: string; sizeBytes: number; sha256?: string; version: number; remark?: string; uploaderId: number; uploaderName?: string; createdAt: string }
