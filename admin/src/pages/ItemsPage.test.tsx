import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchItems } from '../api/items'
import { fetchSettings } from '../api/settings'
import type { Item } from '../types'
import { ItemsPage } from './ItemsPage'

vi.mock('../api/items')
vi.mock('../api/settings')

const item = (id: number, type: Item['type'], value: string, cron: string, nextSendAt: string): Item => ({
  id, type, value, cron, nextSendAt, createdAt: '2026-10-25T00:00:00Z',
})

describe('ItemsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchSettings).mockResolvedValue({ excludeKeywords: ['교육'], categories: ['GOODS', 'SERVICE', 'CONSTRUCTION', 'FOREIGN'], priceMin: null, priceMax: null })
  })

  it('주기별 카드에 항목을 태그로 묶고 공통 조건을 요약한다', async () => {
    vi.mocked(fetchItems).mockResolvedValue([
      item(1, 'KEYWORD', 'ODA', '0 */5 * * * *', '2026-10-31T05:05:00Z'),
      item(2, 'AGENCY', '한국국제협력단', '0 */5 * * * *', '2026-10-31T05:05:00Z'),
      item(3, 'KEYWORD', '의료', '0 0 9 * * *', '2026-11-01T00:00:00Z'),
    ])
    render(<MemoryRouter><ItemsPage /></MemoryRouter>)

    const immediate = await screen.findByRole('article', { name: '매일 하루 종일, 즉시(5분마다)' })
    expect(within(immediate).getByRole('link', { name: 'ODA' })).toHaveAttribute('href', '/items/1')
    expect(within(immediate).getByRole('link', { name: '🏛 한국국제협력단' })).toHaveAttribute('href', '/items/2')
    expect(within(immediate).getByText(/2개/)).toBeInTheDocument()
    expect(screen.getByRole('article', { name: '매일 09:00 하루 1번' })).toBeInTheDocument()
    expect(screen.getByText(/제외 키워드 1개 · 업무구분 전체 · 가격 제한 없음/)).toBeInTheDocument()
  })

  it('항목이 없으면 안내를 보여 준다', async () => {
    vi.mocked(fetchItems).mockResolvedValue([])
    render(<MemoryRouter><ItemsPage /></MemoryRouter>)

    expect(await screen.findByText('아직 등록한 키워드·수요기관이 없습니다')).toBeInTheDocument()
  })
})
