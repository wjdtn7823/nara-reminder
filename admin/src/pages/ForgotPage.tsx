import { type FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { requestPasswordReset } from '../api/auth'
import { ApiError } from '../api/client'

export function ForgotPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      await requestPasswordReset(email)
      setSent(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '요청에 실패했습니다')
    }
  }

  return (
    <section className="card auth" aria-label="비밀번호 재설정 요청">
      <h2>비밀번호 재설정</h2>
      {sent ? (
        <p role="status">가입된 이메일이면 재설정 링크를 보냈습니다. (30분 유효)</p>
      ) : (
        <form onSubmit={handleSubmit}>
          <label>
            이메일
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </label>
          <button type="submit">재설정 링크 받기</button>
        </form>
      )}
      {error && <p role="alert" className="error">{error}</p>}
      <Link to="/login">로그인 화면으로</Link>
    </section>
  )
}
