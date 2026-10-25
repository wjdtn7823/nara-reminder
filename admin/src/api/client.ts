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

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } })
  if (!response.ok) {
    const body: unknown = await response.json().catch(() => null)
    const error = isErrorBody(body) ? body : { code: 'UNKNOWN', message: `요청 실패 (${response.status})` }
    throw new ApiError(response.status, error.code, error.message)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
