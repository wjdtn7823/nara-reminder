import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, login } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { LoginPage } from './LoginPage'

vi.mock('../api/auth')

const routes = [
  { path: '/login', element: <LoginPage /> },
  { path: '/items', element: <p>내 알림 화면</p> },
]

describe('LoginPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  it('로그인하면 내 알림 화면으로 간다', async () => {
    vi.mocked(login).mockResolvedValue({ email: 'me@example.com', admin: false })
    renderRoutes(routes, '/login')

    await userEvent.type(screen.getByLabelText('이메일'), 'me@example.com')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'correct-horse')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(login).toHaveBeenCalledWith('me@example.com', 'correct-horse')
    expect(await screen.findByText('내 알림 화면')).toBeInTheDocument()
  })

  it('실패하면 서버 메시지를 보여 준다', async () => {
    vi.mocked(login).mockRejectedValue(new ApiError(423, 'ACCOUNT_LOCKED', '로그인을 5번 실패해 15분 동안 잠겼습니다'))
    renderRoutes(routes, '/login')

    await userEvent.type(screen.getByLabelText('이메일'), 'me@example.com')
    await userEvent.type(screen.getByLabelText('비밀번호'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('15분 동안 잠겼습니다')
  })
})
