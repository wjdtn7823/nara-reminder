export interface Me {
  email: string
  admin: boolean
}

export type Category = 'GOODS' | 'SERVICE' | 'CONSTRUCTION' | 'FOREIGN'
export type JobName = 'BID_POLLING' | 'PRE_SPEC_DAILY'
export type RunStatus = 'SUCCESS' | 'FAILURE'

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

export interface CronPreview {
  cron: string
  next: string[]
}

export type ItemType = 'KEYWORD' | 'AGENCY'
export type DeliveryStatus = 'PENDING' | 'SENT' | 'FAILED'
export type NoticeSource = 'BID' | 'PRE_SPEC'

export interface Item {
  id: number
  type: ItemType
  value: string
  cron: string
  nextSendAt: string | null
  createdAt: string
}

export interface RecentNotice {
  id: number
  status: DeliveryStatus
  matchedAt: string
  sentAt: string | null
  scheduledAt: string | null
  source: NoticeSource
  noticeNo: string
  title: string
  agency: string | null
  price: number | null
  deadlineAt: string | null
  detailUrl: string | null
  revision: boolean
}

export interface ItemDetail {
  item: Item
  stats: { matched: number; sent: number; pending: number }
  recent: RecentNotice[]
}

export interface Settings {
  excludeKeywords: string[]
  categories: Category[]
  priceMin: number | null
  priceMax: number | null
}

export const ITEM_TYPE_LABELS: Record<ItemType, string> = { KEYWORD: '키워드', AGENCY: '수요기관' }
export const SOURCE_LABELS: Record<NoticeSource, string> = { BID: '입찰', PRE_SPEC: '사전규격' }
