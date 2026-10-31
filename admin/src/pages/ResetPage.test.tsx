import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, resetPassword } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { ResetPage } from './ResetPage'

vi.mock('../api/auth')

describe('ResetPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  it('새 비밀번호를 정하면 로그인 안내를 보여 준다', async () => {
    vi.mocked(resetPassword).mockResolvedValue(undefined)
    renderRoutes([{ path: '/reset', element: <ResetPage /> }], '/reset?token=tok123')

    await userEvent.type(screen.getByLabelText('새 비밀번호'), 'new-password')
    await userEvent.type(screen.getByLabelText('새 비밀번호 확인'), 'new-password')
    await userEvent.click(screen.getByRole('button', { name: '비밀번호 바꾸기' }))

    expect(resetPassword).toHaveBeenCalledWith('tok123', 'new-password')
    expect(await screen.findByRole('status')).toHaveTextContent('비밀번호를 바꿨습니다')
  })
})
