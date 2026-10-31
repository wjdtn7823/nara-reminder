import type { Me } from '../types'
import { request } from './client'

export interface SignupInput {
  inviteCode: string
  email: string
  password: string
}

const post = <T>(path: string, body?: object): Promise<T> =>
  request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined })

export const fetchMe = (): Promise<Me> => request<Me>('/api/me')
export const login = (email: string, password: string): Promise<Me> => post<Me>('/api/auth/login', { email, password })
export const logout = (): Promise<void> => post<void>('/api/auth/logout')
export const signup = (input: SignupInput): Promise<void> => post<void>('/api/auth/signup', input)
export const verifyEmail = (token: string): Promise<void> => post<void>('/api/auth/verify', { token })
export const requestPasswordReset = (email: string): Promise<void> => post<void>('/api/auth/forgot', { email })
export const resetPassword = (token: string, password: string): Promise<void> => post<void>('/api/auth/reset', { token, password })
