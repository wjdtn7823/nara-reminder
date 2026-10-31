import { describe, expect, it } from 'vitest'
import { groupByCron, summarizeSettings } from './itemGroups'
import type { Item } from './types'

const item = (id: number, cron: string, nextSendAt: string | null): Item => ({
  id, type: 'KEYWORD', value: `키워드${id}`, cron, nextSendAt, createdAt: '2026-10-25T00:00:00Z',
})

describe('groupByCron', () => {
  it('같은 주기끼리 묶고 다음 발송이 빠른 묶음부터 놓는다', () => {
    const groups = groupByCron([
      item(1, '0 0 9 * * *', '2026-11-01T00:00:00Z'),
      item(2, '0 */5 * * * *', '2026-10-31T05:05:00Z'),
      item(3, '0 0 9 * * *', '2026-11-01T00:00:00Z'),
    ])

    expect(groups.map((g) => g.cron)).toEqual(['0 */5 * * * *', '0 0 9 * * *'])
    expect(groups[1].items.map((i) => i.id)).toEqual([1, 3])
  })
})

describe('summarizeSettings', () => {
  it('제외 키워드 수·업무구분·가격 범위를 한 줄로 요약한다', () => {
    expect(summarizeSettings({ excludeKeywords: ['교육', '청소'], categories: ['GOODS', 'SERVICE', 'CONSTRUCTION', 'FOREIGN'], priceMin: null, priceMax: null }))
      .toBe('제외 키워드 2개 · 업무구분 전체 · 가격 제한 없음')
    expect(summarizeSettings({ excludeKeywords: [], categories: ['SERVICE'], priceMin: 1000, priceMax: null }))
      .toBe('제외 키워드 0개 · 업무구분 용역 · 가격 1,000원 이상')
  })
})
