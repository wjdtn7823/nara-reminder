import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createInvite, fetchInvites, revokeInvite } from '../api/admin'
import type { Invite } from '../types'
import { InvitesPage } from './InvitesPage'

vi.mock('../api/admin')

const invite = (id: number, code: string, status: Invite['status']): Invite => ({
  id, code, status, maxUses: 5, usedCount: 1, expiresAt: '2026-11-07T04:40:00Z', createdAt: '2026-10-31T04:40:00Z',
})

describe('InvitesPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchInvites).mockResolvedValue([invite(2, 'B3HN-9WTE', 'ACTIVE'), invite(1, 'P8VF-6YJS', 'REVOKED')])
  })

  it('코드별 상태와 사용 횟수를 보여 주고 사용 가능한 코드만 회수 버튼이 있다', async () => {
    render(<InvitesPage />)

    const active = await screen.findByRole('row', { name: /B3HN-9WTE/ })
    expect(within(active).getByText('사용 가능')).toBeInTheDocument()
    expect(within(active).getByText('1 / 5')).toBeInTheDocument()
    expect(within(active).getByRole('button', { name: '회수' })).toBeInTheDocument()
    const revoked = screen.getByRole('row', { name: /P8VF-6YJS/ })
    expect(within(revoked).getByText('회수됨')).toBeInTheDocument()
    expect(within(revoked).queryByRole('button', { name: '회수' })).not.toBeInTheDocument()
  })

  it('만들면 새 코드와 가입 링크를 보여 준다', async () => {
    vi.mocked(createInvite).mockResolvedValue(invite(3, 'K7QM-2XPA', 'ACTIVE'))
    render(<InvitesPage />)

    const maxUses = await screen.findByLabelText('사용 한도')
    await userEvent.clear(maxUses)
    await userEvent.type(maxUses, '3')
    await userEvent.click(screen.getByRole('button', { name: '만들기' }))

    expect(createInvite).toHaveBeenCalledWith({ maxUses: 3, validDays: 7 })
    const created = await screen.findByRole('status')
    expect(created).toHaveTextContent('K7QM-2XPA')
    expect(created).toHaveTextContent('/signup?code=K7QM-2XPA')
  })

  it('회수를 확인하면 회수한다', async () => {
    vi.mocked(revokeInvite).mockResolvedValue(invite(2, 'B3HN-9WTE', 'REVOKED'))
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    render(<InvitesPage />)

    const active = await screen.findByRole('row', { name: /B3HN-9WTE/ })
    await userEvent.click(within(active).getByRole('button', { name: '회수' }))

    expect(revokeInvite).toHaveBeenCalledWith(2)
  })
})
