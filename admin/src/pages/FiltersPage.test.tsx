import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchFilters, updateSettings } from '../api/filters'
import type { Filters } from '../types'
import { FiltersPage } from './FiltersPage'

vi.mock('../api/filters')

const base: Filters = { keywords: [], excludeKeywords: [], agencies: [], categories: ['SERVICE'], priceMin: null, priceMax: null }

describe('FiltersPage', () => {
  beforeEach(() => vi.resetAllMocks())

  it('세 목록 카드를 보여 준다', async () => {
    vi.mocked(fetchFilters).mockResolvedValue({ ...base, keywords: [{ id: 1, type: 'KEYWORD', value: '타당성' }] })
    render(<FiltersPage />)
    expect(await screen.findByText('타당성')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '키워드' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '제외 키워드' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '수요기관' })).toBeInTheDocument()
  })

  it('키워드와 수요기관이 모두 비면 경고를 보여 준다', async () => {
    vi.mocked(fetchFilters).mockResolvedValue(base)
    render(<FiltersPage />)
    expect(await screen.findByText(/모든 공고가 메일로 발송됩니다/)).toBeInTheDocument()
  })

  it('설정을 저장한 뒤에도 적용 안내가 유지되고 다시 불러오지 않는다', async () => {
    vi.mocked(fetchFilters).mockResolvedValue(base)
    vi.mocked(updateSettings).mockResolvedValue({ ...base, categories: ['SERVICE', 'GOODS'] })
    render(<FiltersPage />)
    await userEvent.click(await screen.findByLabelText('물품'))
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByRole('status')).toHaveTextContent('다음 수집(최대 5분)부터 적용됩니다')
    expect(fetchFilters).toHaveBeenCalledTimes(1)
  })
})
