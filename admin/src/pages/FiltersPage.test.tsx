import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchFilters } from '../api/filters'
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
})
