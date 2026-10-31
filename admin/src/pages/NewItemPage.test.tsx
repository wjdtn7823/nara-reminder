import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { previewCron } from '../api/cron'
import { createItem } from '../api/items'
import { IMMEDIATE_CRON } from '../cadence'
import { NewItemPage } from './NewItemPage'

vi.mock('../api/items')
vi.mock('../api/cron')

const renderPage = () =>
  render(
    <MemoryRouter initialEntries={['/items/new']}>
      <Routes>
        <Route path="/items/new" element={<NewItemPage />} />
        <Route path="/items" element={<p>목록 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )

describe('NewItemPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(previewCron).mockResolvedValue({ cron: IMMEDIATE_CRON, next: [] })
  })

  it('수요기관을 기본 주기(즉시)로 추가하고 목록으로 돌아간다', async () => {
    vi.mocked(createItem).mockResolvedValue({ id: 9, type: 'AGENCY', value: '조달청', cron: IMMEDIATE_CRON, nextSendAt: null, createdAt: '' })
    renderPage()

    await userEvent.click(screen.getByLabelText('수요기관'))
    await userEvent.type(screen.getByLabelText('값'), '조달청')
    await userEvent.click(screen.getByRole('button', { name: '추가' }))

    expect(createItem).toHaveBeenCalledWith({ type: 'AGENCY', value: '조달청', cron: IMMEDIATE_CRON })
    expect(await screen.findByText('목록 화면')).toBeInTheDocument()
  })

  it('서버가 거절하면 이유를 보여 준다', async () => {
    vi.mocked(createItem).mockRejectedValue(new ApiError(409, 'DUPLICATE', '이미 등록된 키워드입니다: ODA'))
    renderPage()

    await userEvent.type(screen.getByLabelText('값'), 'ODA')
    await userEvent.click(screen.getByRole('button', { name: '추가' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('이미 등록된 키워드입니다: ODA')
  })
})
