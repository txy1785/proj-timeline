/** Deletions and status changes can shrink a filtered list while the last page is open. */
export function boundedPage(total: number, size: number, requested: number): number {
  return Math.max(1, Math.min(requested, Math.ceil(total / size)))
}
