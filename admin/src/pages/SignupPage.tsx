import { type FormEvent, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { signup } from '../api/auth'
import { ApiError } from '../api/client'

export function SignupPage() {
  const [params] = useSearchParams()
  const [inviteCode, setInviteCode] = useState(params.get('code') ?? '')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState(false)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (password !== confirm) return setError('비밀번호 확인이 일치하지 않습니다')
    setError(null)
    try {
      await signup({ inviteCode, email, password })
      setSent(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '가입 요청에 실패했습니다')
    }
  }

  if (sent) {
    return (
      <section className="card auth" aria-label="가입">
        <p role="status">확인 메일을 보냈습니다. 메일의 링크를 눌러 가입을 마치세요. (24시간 유효)</p>
        <Link to="/login">로그인 화면으로</Link>
      </section>
    )
  }

  return (
    <section className="card auth" aria-label="가입">
      <h2>초대 코드로 가입</h2>
      <form onSubmit={handleSubmit}>
        <label>
          초대 코드
          <input value={inviteCode} onChange={(e) => setInviteCode(e.target.value)} required />
        </label>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} required />
        </label>
        <label>
          비밀번호 확인
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required />
        </label>
        <p className="muted">비밀번호는 8자 이상(72바이트 이하)입니다. 미리 만들어 둔 계정이 있으면 같은 이메일로 가입하면 이어받습니다.</p>
        <button type="submit">가입 요청</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  )
}
