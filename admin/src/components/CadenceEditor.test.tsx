import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { previewCron } from '../api/cron'
import { CadenceEditor } from './CadenceEditor'

vi.mock('../api/cron')

function Harness({ initial, onChange }: { initial: string; onChange: (cron: string) => void }) {
  const [cron, setCron] = useState(initial)
  return (
    <CadenceEditor
      value={cron}
      onChange={(next) => {
        setCron(next)
        onChange(next)
      }}
    />
  )
}

describe('CadenceEditor', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(previewCron).mockImplementation((expr: string) =>
      Promise.resolve({ cron: expr, next: ['2026-10-31T05:30:00Z', '2026-10-31T06:00:00Z', '2026-10-31T06:30:00Z'] }),
    )
  })

  it('현재 주기를 문장·다음 발송·cron 으로 보여 준다', async () => {
    render(<Harness initial="0 */30 9-17 * * MON-FRI" onChange={vi.fn()} />)

    expect(screen.getByText('📬 평일 09:00~18:00 전까지, 30분마다')).toBeInTheDocument()
    expect(await screen.findByText(/14:30/)).toBeInTheDocument()
    expect(screen.getByText('0 */30 9-17 * * MON-FRI')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '평일' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('요일·하루 종일·주기를 고르면 cron 을 만들어 알린다', async () => {
    const onChange = vi.fn()
    render(<Harness initial="0 */30 9-17 * * MON-FRI" onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: '주말' }))
    expect(onChange).toHaveBeenLastCalledWith('0 */30 9-17 * * SAT,SUN')

    await userEvent.click(screen.getByLabelText('하루 종일'))
    expect(onChange).toHaveBeenLastCalledWith('0 */30 * * * SAT,SUN')

    await userEvent.click(screen.getByRole('button', { name: '1시간마다' }))
    expect(onChange).toHaveBeenLastCalledWith('0 0 * * * SAT,SUN')
  })

  it('시작 시각을 끝 시각 이후로 옮기면 끝 시각이 따라간다', async () => {
    const onChange = vi.fn()
    render(<Harness initial="0 */30 9-17 * * MON-FRI" onChange={onChange} />)

    await userEvent.selectOptions(screen.getByLabelText('시작 시각'), '20')

    expect(onChange).toHaveBeenLastCalledWith('0 */30 20 * * MON-FRI')
  })

  it('cron 을 직접 고치면 적용하고, 만들 수 없는 식은 사용자 지정으로 보인다', async () => {
    const onChange = vi.fn()
    render(<Harness initial="0 */5 * * * *" onChange={onChange} />)

    await userEvent.click(screen.getByRole('button', { name: '직접 편집' }))
    const input = screen.getByLabelText('cron 식')
    await userEvent.clear(input)
    await userEvent.type(input, '0 15 10 * * *')
    await userEvent.click(screen.getByRole('button', { name: '적용' }))

    expect(onChange).toHaveBeenLastCalledWith('0 15 10 * * *')
    expect(screen.getByText('📬 사용자 지정 (0 15 10 * * *)')).toBeInTheDocument()
  })

  it('서버가 거절한 cron 은 이유를 보여 준다', async () => {
    vi.mocked(previewCron).mockRejectedValue(new ApiError(400, 'INVALID_CRON', '발송 간격은 5분 이상이어야 합니다'))
    render(<Harness initial="0 * * * * *" onChange={vi.fn()} />)

    expect(await screen.findByText('발송 간격은 5분 이상이어야 합니다')).toBeInTheDocument()
  })
})
