import { screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from '../api/client'
import { fetchMe } from '../api/auth'
import { Header } from '../components/Header'
import { useEffect } from 'react'
import { renderRoutes } from '../test/renderRoutes'
import { RequireAuth } from './AuthContext'

vi.mock('../api/auth')

function ExpiredPage() {
  useEffect(() => {
    request('/api/items').catch(() => undefined)
  }, [])
  return <p>만료 확인 화면</p>
}

const routes = [
  { path: '/login', element: <p>로그인 화면</p> },
  { path: '/items', element: <><Header /><RequireAuth><p>내 알림 화면</p></RequireAuth></> },
  { path: '/expired', element: <><Header /><RequireAuth><ExpiredPage /></RequireAuth></> },
  { path: '/admin/runs', element: <RequireAuth admin><p>실행 이력 화면</p></RequireAuth> },
]

describe('RequireAuth / Header', () => {
  beforeEach(() => vi.resetAllMocks())
  afterEach(() => vi.unstubAllGlobals())

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

  it('세션 중간에 보호된 API 가 401 이면 로그인 화면으로 보낸다', async () => {
    vi.mocked(fetchMe).mockResolvedValue({ email: 'me@example.com', admin: false })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"code":"UNAUTHORIZED","message":"로그인 필요"}', { status: 401 })))
    renderRoutes(routes, '/expired')

    await waitFor(() => expect(screen.getByText('로그인 화면')).toBeInTheDocument())
  })
})
