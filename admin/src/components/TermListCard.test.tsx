import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { addTerm, deleteTerm, updateTerm } from '../api/filters'
import { ApiError } from '../api/client'
import type { Term } from '../types'
import { TermListCard } from './TermListCard'

vi.mock('../api/filters')

const terms: Term[] = [
  { id: 1, type: 'KEYWORD', value: '타당성' },
  { id: 2, type: 'KEYWORD', value: 'ODA' },
]

describe('TermListCard', () => {
  const onChanged = vi.fn()
  beforeEach(() => vi.resetAllMocks())

  const renderCard = () => render(<TermListCard title="키워드" type="KEYWORD" terms={terms} onChanged={onChanged} />)

  it('목록과 개수를 보여 준다', () => {
    renderCard()
    expect(screen.getByText('타당성')).toBeInTheDocument()
    expect(screen.getByText('ODA')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
  })

  it('추가하면 API 를 호출하고 입력칸을 비운다', async () => {
    vi.mocked(addTerm).mockResolvedValue({ id: 3, type: 'KEYWORD', value: '컨설팅' })
    renderCard()
    await userEvent.type(screen.getByLabelText('키워드 입력'), '컨설팅')
    await userEvent.click(screen.getByRole('button', { name: '추가' }))
    expect(addTerm).toHaveBeenCalledWith('KEYWORD', '컨설팅')
    expect(onChanged).toHaveBeenCalled()
    expect(screen.getByLabelText('키워드 입력')).toHaveValue('')
  })

  it('중복이면 서버 메시지를 보여 준다', async () => {
    vi.mocked(addTerm).mockRejectedValue(new ApiError(409, 'DUPLICATE', '이미 등록된 키워드입니다: oda'))
    renderCard()
    await userEvent.type(screen.getByLabelText('키워드 입력'), 'oda')
    await userEvent.click(screen.getByRole('button', { name: '추가' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('이미 등록된 키워드입니다: oda')
    expect(onChanged).not.toHaveBeenCalled()
  })

  it('항목을 수정한다', async () => {
    vi.mocked(updateTerm).mockResolvedValue({ id: 1, type: 'KEYWORD', value: '타당성조사' })
    renderCard()
    await userEvent.click(screen.getAllByRole('button', { name: '수정' })[0])
    const input = screen.getByLabelText('타당성 수정')
    await userEvent.clear(input)
    await userEvent.type(input, '타당성조사')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(updateTerm).toHaveBeenCalledWith(1, '타당성조사')
  })

  it('확인하면 항목을 삭제한다', async () => {
    vi.mocked(deleteTerm).mockResolvedValue(undefined)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderCard()
    await userEvent.click(screen.getAllByRole('button', { name: '삭제' })[1])
    expect(deleteTerm).toHaveBeenCalledWith(2)
    expect(onChanged).toHaveBeenCalled()
  })
})
