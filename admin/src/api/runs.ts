import type { JobName, JobRun, Page, RunStatus } from '../types'
import { request } from './client'

export interface RunQuery {
  job: JobName | ''
  status: RunStatus | ''
  page: number
}

export const fetchRuns = ({ job, status, page }: RunQuery): Promise<Page<JobRun>> => {
  const params = new URLSearchParams({ page: String(page), size: '50' })
  if (job) params.set('job', job)
  if (status) params.set('status', status)
  return request<Page<JobRun>>(`/api/runs?${params.toString()}`)
}
