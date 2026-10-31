import type { Settings } from '../types'
import { request } from './client'

export const fetchSettings = (): Promise<Settings> => request<Settings>('/api/settings')
export const updateSettings = (settings: Settings): Promise<Settings> =>
  request<Settings>('/api/settings', { method: 'PUT', body: JSON.stringify(settings) })
