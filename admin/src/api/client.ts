export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

interface ErrorBody {
  code: string
  message: string
}

const isErrorBody = (body: unknown): body is ErrorBody =>
  typeof body === 'object' && body !== null && 'code' in body && 'message' in body

const readCookie = (name: string): string | null => {
  const entry = document.cookie.split('; ').find((cookie) => cookie.startsWith(`${name}=`))
  return entry ? decodeURIComponent(entry.slice(name.length + 1)) : null
}

let unauthorizedHandler: (() => void) | null = null

export function onUnauthorized(handler: (() => void) | null) {
  unauthorizedHandler = handler
}

let csrfPrefetch: Promise<void> | null = null

// 동시에 여러 변경 요청이 나가도 쿠키 확보 요청은 한 번만 보낸다
const prefetchCsrfCookie = (): Promise<void> => {
  csrfPrefetch ??= fetch('/api/me')
    .then(() => undefined)
    .catch(() => undefined)
    .finally(() => {
      csrfPrefetch = null
    })
  return csrfPrefetch
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  // 서버가 내려준 XSRF-TOKEN 쿠키를 변경 요청 헤더로 돌려보낸다 (Spring Security CSRF)
  let csrf = method === 'GET' ? null : readCookie('XSRF-TOKEN')
  if (method !== 'GET' && !csrf) {
    // 첫 요청이거나 로그아웃으로 쿠키가 지워진 경우: /api/me 응답 상태와 상관없이 쿠키만 받아 온다
    await prefetchCsrfCookie()
    csrf = readCookie('XSRF-TOKEN')
  }
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}) }
  const response = await fetch(path, { ...init, headers: { ...headers, ...init?.headers } })
  const pathname = path.split('?')[0]
  if (response.status === 401 && !pathname.startsWith('/api/auth/') && pathname !== '/api/me') unauthorizedHandler?.()
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)
    const error = isErrorBody(body) ? body : { code: 'UNKNOWN', message: `요청 실패 (${response.status})` }
    throw new ApiError(response.status, error.code, error.message)
  }
  const text = await response.text()
  const body: unknown = text ? JSON.parse(text) : undefined
  return body as T
}
