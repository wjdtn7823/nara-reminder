import type { Item, ItemDetail, ItemType } from '../types'
import { request } from './client'

export interface ItemInput {
  type: ItemType
  value: string
  cron: string
}

export interface ItemPatch {
  value?: string
  cron?: string
}

export const fetchItems = (): Promise<Item[]> => request<Item[]>('/api/items')
export const fetchItem = (id: number): Promise<ItemDetail> => request<ItemDetail>(`/api/items/${id}`)
export const createItem = (input: ItemInput): Promise<Item> => request<Item>('/api/items', { method: 'POST', body: JSON.stringify(input) })
export const updateItem = (id: number, patch: ItemPatch): Promise<Item> =>
  request<Item>(`/api/items/${id}`, { method: 'PATCH', body: JSON.stringify(patch) })
export const deleteItem = (id: number): Promise<void> => request<void>(`/api/items/${id}`, { method: 'DELETE' })
