import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, verifyEmail } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { VerifyPage } from './VerifyPage'

vi.mock('../api/auth')

describe('VerifyPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  it('주소의 토큰으로 한 번만 확인하고 완료를 알린다', async () => {
    vi.mocked(verifyEmail).mockResolvedValue(undefined)
    renderRoutes([{ path: '/verify', element: <VerifyPage /> }], '/verify?token=tok123')

    expect(await screen.findByText(/가입이 끝났습니다/)).toBeInTheDocument()
    expect(verifyEmail).toHaveBeenCalledTimes(1)
    expect(verifyEmail).toHaveBeenCalledWith('tok123')
  })

  it('실패하면 서버 메시지를 보여 준다', async () => {
    vi.mocked(verifyEmail).mockRejectedValue(new ApiError(400, 'INVALID_TOKEN', '링크가 만료되었거나 이미 사용되었습니다'))
    renderRoutes([{ path: '/verify', element: <VerifyPage /> }], '/verify?token=old')

    expect(await screen.findByRole('alert')).toHaveTextContent('링크가 만료되었거나')
  })
})
