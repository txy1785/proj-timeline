import type { User } from '../api/types'

/**
 * 可作为负责人/执行人的项目成员：有效且非 VIEWER。
 * 口径只有一份（`requireAssignable` 在服务端同样拒绝停用与 VIEWER），页面不要各写一份 filter。
 */
export function selectableMembers(members: User[]): User[] {
  return members.filter((item) => item.active && item.globalRole !== 'VIEWER')
}
