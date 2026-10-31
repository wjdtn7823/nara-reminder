import type { Invite } from '../types'
import { request } from './client'

export interface InviteInput {
  maxUses: number
  validDays: number
}

export const fetchInvites = (): Promise<Invite[]> => request<Invite[]>('/api/admin/invites')

export const createInvite = (input: InviteInput): Promise<Invite> =>
  request<Invite>('/api/admin/invites', { method: 'POST', body: JSON.stringify(input) })

export const revokeInvite = (id: number): Promise<Invite> =>
  request<Invite>(`/api/admin/invites/${id}/revoke`, { method: 'POST' })
