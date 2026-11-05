/** Date-only arithmetic. Business today must come from /context, never the browser clock. */
export function dateValue(value: string): number {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) ? Date.parse(`${value}T00:00:00Z`) : Number.NaN
}

export function addDays(value: string, amount: number): string {
  const stamp = dateValue(value)
  return Number.isFinite(stamp) ? new Date(stamp + amount * 86400000).toISOString().slice(0, 10) : ''
}

export function daysBetween(start: string, end: string): number {
  return Math.round((dateValue(end) - dateValue(start)) / 86400000)
}

export function weekStart(value: string): string {
  const stamp = dateValue(value)
  if (!Number.isFinite(stamp)) return ''
  return addDays(value, -((new Date(stamp).getUTCDay() + 6) % 7))
}

export function shortDate(value?: string | null): string {
  return value ? value.slice(5).replace('-', '/') : '未设置'
}

/** 升序比较，缺失日期（待排期）排在最后。 */
export function compareOptionalDates(left?: string | null, right?: string | null): number {
  if (!left || !right) return left ? -1 : right ? 1 : 0
  return left.localeCompare(right)
}
