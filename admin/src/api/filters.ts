import type { Category, Filters, Term, TermType } from '../types'
import { request } from './client'

export interface SettingsInput {
  categories: Category[]
  priceMin: number | null
  priceMax: number | null
}

export const fetchFilters = (): Promise<Filters> => request<Filters>('/api/filters')

export const addTerm = (type: TermType, value: string): Promise<Term> =>
  request<Term>('/api/filters/terms', { method: 'POST', body: JSON.stringify({ type, value }) })

export const updateTerm = (id: number, value: string): Promise<Term> =>
  request<Term>(`/api/filters/terms/${id}`, { method: 'PUT', body: JSON.stringify({ value }) })

export const deleteTerm = (id: number): Promise<void> => request<void>(`/api/filters/terms/${id}`, { method: 'DELETE' })

export const updateSettings = (input: SettingsInput): Promise<Filters> =>
  request<Filters>('/api/filters/settings', { method: 'PUT', body: JSON.stringify(input) })
