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

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const method = (init?.method ?? 'GET').toUpperCase()
  // 서버가 내려준 XSRF-TOKEN 쿠키를 변경 요청 헤더로 돌려보낸다 (Spring Security CSRF)
  const csrf = method === 'GET' ? null : readCookie('XSRF-TOKEN')
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}) }
  const response = await fetch(path, { ...init, headers: { ...headers, ...init?.headers } })
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)
    const error = isErrorBody(body) ? body : { code: 'UNKNOWN', message: `요청 실패 (${response.status})` }
    throw new ApiError(response.status, error.code, error.message)
  }
  const text = await response.text()
  const body: unknown = text ? JSON.parse(text) : undefined
  return body as T
}
