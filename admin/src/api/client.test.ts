import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { onUnauthorized, request } from './client'

const respond = (status: number, body: string) => vi.fn().mockResolvedValue(new Response(body || null, { status }))

describe('request', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=token%2Babc'
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  })

  it('변경 요청에는 XSRF-TOKEN 쿠키 값을 헤더로 붙인다', async () => {
    const fetchMock = respond(200, '{"ok":true}')
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/items', { method: 'POST', body: '{}' })

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'token+abc' })
  })

  it('GET 에는 CSRF 헤더를 붙이지 않는다', async () => {
    const fetchMock = respond(200, '[]')
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/items')

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('본문이 없으면 undefined, 오류면 ApiError 를 던진다', async () => {
    vi.stubGlobal('fetch', respond(202, ''))
    await expect(request('/api/auth/signup', { method: 'POST' })).resolves.toBeUndefined()

    vi.stubGlobal('fetch', respond(409, '{"code":"DUPLICATE","message":"이미 등록된 키워드입니다"}'))
    await expect(request('/api/items', { method: 'POST' })).rejects.toMatchObject({ status: 409, code: 'DUPLICATE', message: '이미 등록된 키워드입니다' })
  })

  it('XSRF-TOKEN 쿠키가 없으면 /api/me 로 먼저 받아 온 뒤 변경 요청에 토큰을 붙인다', async () => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
    const fetchMock = vi.fn().mockImplementation(async (path: string) => {
      if (path === '/api/me') {
        document.cookie = 'XSRF-TOKEN=fresh'
        return new Response(null, { status: 401 })
      }
      return new Response(null, { status: 204 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await request('/api/auth/login', { method: 'POST', body: '{}' })

    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual(['/api/me', '/api/auth/login'])
    const init = fetchMock.mock.calls[1][1] as RequestInit
    expect(init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'fresh' })
  })

  describe('401 처리', () => {
    afterEach(() => onUnauthorized(null))

    it('인증이 필요한 API 가 401 이면 핸들러를 부른다', async () => {
      const handler = vi.fn()
      onUnauthorized(handler)
      vi.stubGlobal('fetch', respond(401, '{"code":"UNAUTHORIZED","message":"로그인 필요"}'))

      await expect(request('/api/items')).rejects.toMatchObject({ status: 401 })

      expect(handler).toHaveBeenCalledTimes(1)
    })

    it('/api/me 와 /api/auth/ 의 401 은 핸들러를 부르지 않는다', async () => {
      const handler = vi.fn()
      onUnauthorized(handler)
      vi.stubGlobal('fetch', respond(401, '{"code":"UNAUTHORIZED","message":"로그인 필요"}'))

      await expect(request('/api/me')).rejects.toMatchObject({ status: 401 })
      await expect(request('/api/auth/login', { method: 'POST' })).rejects.toMatchObject({ status: 401 })

      expect(handler).not.toHaveBeenCalled()
    })
  })
})
