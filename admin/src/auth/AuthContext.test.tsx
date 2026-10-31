import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe } from '../api/auth'
import { Header } from '../components/Header'
import { renderRoutes } from '../test/renderRoutes'
import { RequireAuth } from './AuthContext'

vi.mock('../api/auth')

const routes = [
  { path: '/login', element: <p>로그인 화면</p> },
  { path: '/items', element: <><Header /><RequireAuth><p>내 알림 화면</p></RequireAuth></> },
  { path: '/admin/runs', element: <RequireAuth admin><p>실행 이력 화면</p></RequireAuth> },
]

describe('RequireAuth / Header', () => {
  beforeEach(() => vi.resetAllMocks())

  it('로그인하지 않았으면 로그인 화면으로 보낸다', async () => {
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
    renderRoutes(routes, '/items')

    expect(await screen.findByText('로그인 화면')).toBeInTheDocument()
  })

  it('일반 사용자는 관리자 화면 대신 내 알림으로 가고 관리자 메뉴가 보이지 않는다', async () => {
    vi.mocked(fetchMe).mockResolvedValue({ email: 'me@example.com', admin: false })
    renderRoutes(routes, '/admin/runs')

    expect(await screen.findByText('내 알림 화면')).toBeInTheDocument()
    expect(screen.getByText('me@example.com')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '초대 코드' })).not.toBeInTheDocument()
  })

  it('관리자에게는 관리자 메뉴가 보인다', async () => {
    vi.mocked(fetchMe).mockResolvedValue({ email: 'admin@example.com', admin: true })
    renderRoutes(routes, '/items')

    expect(await screen.findByRole('link', { name: '초대 코드' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '실행 이력' })).toBeInTheDocument()
  })
})
