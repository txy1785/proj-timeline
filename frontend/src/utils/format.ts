/** 后端 LocalDateTime 的 ISO 串转成 `YYYY-MM-DD HH:mm`，空值返回 `—`。 */
export function formatDateTime(value?: string): string {
  if (!value) return '—'
  const match = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/.exec(value)
  return match ? `${match[1]} ${match[2]}` : value
}
