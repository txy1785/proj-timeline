/** 通知类型的中文标签：与后端 `notifications.type` 枚举一一对应，新增类型时同步这里与接口文档。 */
const NOTIFICATION_TYPE_LABELS: Record<string, string> = {
  OVERDUE_ALERT: '延期提醒',
  BLOCK_ALERT: '受阻提醒',
  TASK_ASSIGN: '任务指派',
  NODE_DONE: '工作项完成',
  WEEKLY_PUBLISHED: '周报发布',
  CHANGE_REQUEST_SUBMITTED: '变更待审批',
  CHANGE_REQUEST_DECIDED: '变更审批结果'
}

export function notificationLabel(type: string): string {
  return NOTIFICATION_TYPE_LABELS[type] || '系统通知'
}
