import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { createInvite, fetchInvites, revokeInvite } from '../api/admin'
import { ApiError } from '../api/client'
import { formatKstShort } from '../format'
import { type Invite, INVITE_STATUS_LABELS } from '../types'

export function InvitesPage() {
  const [invites, setInvites] = useState<Invite[]>([])
  const [maxUses, setMaxUses] = useState('5')
  const [validDays, setValidDays] = useState('7')
  const [created, setCreated] = useState<Invite | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => fetchInvites().then(setInvites).catch(() => setError('초대 코드를 불러오지 못했습니다')), [])

  useEffect(() => {
    void load()
  }, [load])

  const handleCreate = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      setCreated(await createInvite({ maxUses: Number(maxUses), validDays: Number(validDays) }))
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '만들지 못했습니다')
    }
  }

  const handleRevoke = async (invite: Invite) => {
    if (!window.confirm(`'${invite.code}' 코드를 회수할까요? 이미 가입한 사람에게는 영향이 없습니다.`)) return
    try {
      await revokeInvite(invite.id)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '회수하지 못했습니다')
    }
  }

  const signupLink = (code: string) => `${window.location.origin}/signup?code=${code}`

  return (
    <section aria-label="초대 코드">
      <section className="card" aria-label="새 초대 코드">
        <h2>새 초대 코드 만들기</h2>
        <form className="row" onSubmit={handleCreate}>
          <label>
            사용 한도
            <input type="number" min={1} max={100} value={maxUses} onChange={(e) => setMaxUses(e.target.value)} />
          </label>
          <label>
            유효 기간(일)
            <input type="number" min={1} max={30} value={validDays} onChange={(e) => setValidDays(e.target.value)} />
          </label>
          <button type="submit">만들기</button>
        </form>
        {created && (
          <div role="status" className="warning">
            만들었습니다: <code>{created.code}</code>
            <div className="muted">팀원에게 이 가입 링크를 전달하세요: {signupLink(created.code)}</div>
            <button type="button" onClick={() => void navigator.clipboard?.writeText(signupLink(created.code))}>
              링크 복사
            </button>
          </div>
        )}
      </section>
      {error && <p role="alert" className="error">{error}</p>}
      <table>
        <thead>
          <tr>
            <th>코드</th><th>상태</th><th>사용</th><th>만료</th><th>만든 날</th><th></th>
          </tr>
        </thead>
        <tbody>
          {invites.map((invite) => (
            <tr key={invite.id}>
              <td><code>{invite.code}</code></td>
              <td>{INVITE_STATUS_LABELS[invite.status]}</td>
              <td>{`${invite.usedCount} / ${invite.maxUses}`}</td>
              <td>{formatKstShort(invite.expiresAt)}</td>
              <td>{formatKstShort(invite.createdAt)}</td>
              <td>
                {invite.status === 'ACTIVE' && (
                  <button type="button" onClick={() => handleRevoke(invite)}>
                    회수
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
