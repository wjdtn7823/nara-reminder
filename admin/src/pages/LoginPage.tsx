import { type FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { login } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'

export function LoginPage() {
  const { setMe } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      setMe(await login(email, password))
      navigate('/items')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '로그인에 실패했습니다')
    }
  }

  return (
    <section className="card auth" aria-label="로그인">
      <h2>로그인</h2>
      <form onSubmit={handleSubmit}>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <button type="submit">로그인</button>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
      <p>
        <Link to="/signup">초대 코드로 가입</Link> · <Link to="/forgot">비밀번호를 잊었어요</Link>
      </p>
    </section>
  )
}
