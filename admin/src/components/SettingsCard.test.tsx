import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { updateSettings } from '../api/filters'
import type { Filters } from '../types'
import { SettingsCard } from './SettingsCard'

vi.mock('../api/filters')

describe('SettingsCard', () => {
  const onSaved = vi.fn()
  beforeEach(() => vi.resetAllMocks())

  const renderCard = () =>
    render(<SettingsCard categories={['GOODS', 'SERVICE']} priceMin={null} priceMax={null} onSaved={onSaved} />)

  it('최소 가격이 최대 가격보다 크면 저장하지 않는다', async () => {
    renderCard()
    await userEvent.type(screen.getByLabelText('최소 가격'), '200')
    await userEvent.type(screen.getByLabelText('최대 가격'), '100')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(screen.getByRole('alert')).toHaveTextContent('최소 가격이 최대 가격보다 클 수 없습니다')
    expect(updateSettings).not.toHaveBeenCalled()
  })

  it('업무구분을 모두 끄면 저장하지 않는다', async () => {
    renderCard()
    await userEvent.click(screen.getByLabelText('물품'))
    await userEvent.click(screen.getByLabelText('용역'))
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(screen.getByRole('alert')).toHaveTextContent('업무구분을 하나 이상 선택하세요')
    expect(updateSettings).not.toHaveBeenCalled()
  })

  it('저장하면 숫자로 변환해 보내고 적용 안내를 보여 준다', async () => {
    const response: Filters = {
      keywords: [], excludeKeywords: [], agencies: [], categories: ['GOODS', 'SERVICE', 'FOREIGN'], priceMin: 1000, priceMax: null,
    }
    vi.mocked(updateSettings).mockResolvedValue(response)
    renderCard()
    await userEvent.click(screen.getByLabelText('외자'))
    await userEvent.type(screen.getByLabelText('최소 가격'), '1000')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(updateSettings).toHaveBeenCalledWith({ categories: ['GOODS', 'SERVICE', 'FOREIGN'], priceMin: 1000, priceMax: null })
    expect(await screen.findByRole('status')).toHaveTextContent('다음 수집(최대 5분)부터 적용됩니다')
    expect(onSaved).toHaveBeenCalledWith(response)
  })
})
