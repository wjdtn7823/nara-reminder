import { type FormEvent, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { resetPassword } from '../api/auth'
import { ApiError } from '../api/client'

export function ResetPage() {
  const [params] = useSearchParams()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (password !== confirm) return setError('비밀번호 확인이 일치하지 않습니다')
    setError(null)
    try {
      await resetPassword(params.get('token') ?? '', password)
      setDone(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '비밀번호를 바꾸지 못했습니다')
    }
  }

  return (
    <section className="card auth" aria-label="비밀번호 재설정">
      <h2>새 비밀번호 정하기</h2>
      {done ? (
        <p role="status">비밀번호를 바꿨습니다. 새 비밀번호로 로그인하세요.</p>
      ) : (
        <form onSubmit={handleSubmit}>
          <label>
            새 비밀번호
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} required />
          </label>
          <label>
            새 비밀번호 확인
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required />
          </label>
          <button type="submit">비밀번호 바꾸기</button>
        </form>
      )}
      {error && <p role="alert" className="error">{error}</p>}
      <Link to="/login">로그인 화면으로</Link>
    </section>
  )
}
