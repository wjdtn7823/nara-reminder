export type TermType = 'KEYWORD' | 'EXCLUDE_KEYWORD' | 'AGENCY'
export type Category = 'GOODS' | 'SERVICE' | 'CONSTRUCTION' | 'FOREIGN'
export type JobName = 'BID_POLLING' | 'PRE_SPEC_DAILY'
export type RunStatus = 'SUCCESS' | 'FAILURE'

export interface Term {
  id: number
  type: TermType
  value: string
}

export interface Filters {
  keywords: Term[]
  excludeKeywords: Term[]
  agencies: Term[]
  categories: Category[]
  priceMin: number | null
  priceMax: number | null
}

export interface JobRun {
  id: number
  jobName: JobName
  startedAt: string
  finishedAt: string
  windowFrom: string
  windowTo: string
  fetchedCount: number
  matchedCount: number
  sentCount: number
  status: RunStatus
  error: string | null
}

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalPages: number
  totalElements: number
}

export const CATEGORIES: Category[] = ['GOODS', 'SERVICE', 'CONSTRUCTION', 'FOREIGN']
export const CATEGORY_LABELS: Record<Category, string> = { GOODS: '물품', SERVICE: '용역', CONSTRUCTION: '공사', FOREIGN: '외자' }
export const JOB_LABELS: Record<JobName, string> = { BID_POLLING: '입찰공고', PRE_SPEC_DAILY: '사전규격' }
