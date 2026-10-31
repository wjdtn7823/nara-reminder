import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from './client'

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
})
