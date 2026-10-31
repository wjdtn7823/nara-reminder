import type { CronPreview } from '../types'
import { request } from './client'

export const previewCron = (expr: string): Promise<CronPreview> =>
  request<CronPreview>(`/api/cron/preview?${new URLSearchParams({ expr }).toString()}`)
