import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchSettings, updateSettings } from '../api/settings'
import type { Settings } from '../types'
import { SettingsPage } from './SettingsPage'

vi.mock('../api/settings')

const settings: Settings = { excludeKeywords: ['교육'], categories: ['GOODS', 'SERVICE'], priceMin: null, priceMax: null }

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchSettings).mockResolvedValue(settings)
    vi.mocked(updateSettings).mockImplementation((next: Settings) => Promise.resolve(next))
  })

  it('제외 키워드를 더하고 빼고 업무구분·가격을 바꿔 한 번에 저장한다', async () => {
    render(<SettingsPage />)

    await userEvent.type(await screen.findByLabelText('제외 키워드 입력'), '청소')
    await userEvent.click(screen.getByRole('button', { name: '제외 키워드 추가' }))
    await userEvent.click(screen.getByRole('button', { name: '교육 삭제' }))
    await userEvent.click(screen.getByLabelText('물품'))
    await userEvent.type(screen.getByLabelText('최소 가격'), '1000')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(updateSettings).toHaveBeenCalledWith({ excludeKeywords: ['청소'], categories: ['SERVICE'], priceMin: 1000, priceMax: null })
    expect(await screen.findByRole('status')).toHaveTextContent('다음 수집부터 적용됩니다')
  })

  it('업무구분을 모두 끄면 저장하지 않는다', async () => {
    render(<SettingsPage />)

    await userEvent.click(await screen.findByLabelText('물품'))
    await userEvent.click(screen.getByLabelText('용역'))
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(updateSettings).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('업무구분을 하나 이상 선택하세요')
  })
})
