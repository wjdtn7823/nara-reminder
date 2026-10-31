import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { previewCron } from '../api/cron'
import { deleteItem, fetchItem, updateItem } from '../api/items'
import type { ItemDetail } from '../types'
import { ItemDetailPage } from './ItemDetailPage'

vi.mock('../api/items')
vi.mock('../api/cron')

const detail: ItemDetail = {
  item: { id: 7, type: 'KEYWORD', value: '타당성', cron: '0 */30 9-17 * * MON-FRI', nextSendAt: '2026-10-31T05:30:00Z', createdAt: '2026-10-25T00:00:00Z' },
  stats: { matched: 27, sent: 25, pending: 2 },
  recent: [
    {
      id: 2, status: 'PENDING', matchedAt: '2026-10-31T05:05:00Z', sentAt: null, scheduledAt: '2026-10-31T05:30:00Z',
      source: 'BID', noticeNo: 'R2', title: '도로 타당성조사', agency: '강원특별자치도', price: 240000000,
      deadlineAt: '2026-11-05T10:00:00', detailUrl: 'https://g2b/R2', revision: true,
    },
    {
      id: 1, status: 'SENT', matchedAt: '2026-10-30T23:00:00Z', sentAt: '2026-10-31T00:00:00Z', scheduledAt: null,
      source: 'PRE_SPEC', noticeNo: 'P1', title: '하수처리장 사전타당성', agency: null, price: null,
      deadlineAt: null, detailUrl: null, revision: false,
    },
  ],
}

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/items/7']}>
      <Routes>
        <Route path="/items/:id" element={<ItemDetailPage />} />
        <Route path="/items" element={<p>목록 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )

describe('ItemDetailPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchItem).mockResolvedValue(detail)
    vi.mocked(previewCron).mockResolvedValue({ cron: detail.item.cron, next: ['2026-10-31T05:30:00Z'] })
  })

  it('설정·최근 7일 통계·최근 걸린 공고와 상태를 보여 준다', async () => {
    renderPage()

    expect(await screen.findByText('🏢 평일 09:00~18:00 전까지, 30분마다')).toBeInTheDocument()
    const stats = screen.getByRole('region', { name: '최근 7일' })
    expect(within(stats).getByText('27')).toBeInTheDocument()
    expect(within(stats).getByText('25')).toBeInTheDocument()
    const pendingRow = screen.getByRole('row', { name: /도로 타당성조사/ })
    expect(within(pendingRow).getByText(/⏳ 대기/)).toBeInTheDocument()
    expect(within(pendingRow).getByText(/14:30 예정/)).toBeInTheDocument()
    expect(within(pendingRow).getByRole('link', { name: '[정정] 도로 타당성조사' })).toHaveAttribute('href', 'https://g2b/R2')
    expect(within(pendingRow).getByText('240,000,000원')).toBeInTheDocument()
    expect(within(pendingRow).getByText('11/05 10:00')).toBeInTheDocument()
    const sentRow = screen.getByRole('row', { name: /하수처리장 사전타당성/ })
    expect(within(sentRow).getByText(/✅ 발송/)).toBeInTheDocument()
    expect(within(sentRow).getByText('사전규격')).toBeInTheDocument()
  })

  it('값을 고쳐 저장한다', async () => {
    vi.mocked(updateItem).mockResolvedValue(detail.item)
    renderPage()

    const input = await screen.findByLabelText('키워드')
    await userEvent.clear(input)
    await userEvent.type(input, '예비타당성')
    await userEvent.click(screen.getByRole('button', { name: '값 저장' }))

    expect(updateItem).toHaveBeenCalledWith(7, { value: '예비타당성' })
  })

  it('주기를 바꾸면 편집기에서 고른 cron 으로 저장한다', async () => {
    vi.mocked(updateItem).mockResolvedValue(detail.item)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: '변경' }))
    await userEvent.click(screen.getByRole('button', { name: '1시간마다' }))
    await userEvent.click(screen.getByRole('button', { name: '주기 저장' }))

    expect(updateItem).toHaveBeenCalledWith(7, { cron: '0 0 9-17 * * MON-FRI' })
  })

  it('삭제를 확인하면 지우고 목록으로 돌아간다', async () => {
    vi.mocked(deleteItem).mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: '삭제' }))

    expect(deleteItem).toHaveBeenCalledWith(7)
    expect(await screen.findByText('목록 화면')).toBeInTheDocument()
  })
})
