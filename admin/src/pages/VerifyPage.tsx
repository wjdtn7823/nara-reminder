import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { verifyEmail } from '../api/auth'
import { ApiError } from '../api/client'

export function VerifyPage() {
  const [params] = useSearchParams()
  const [state, setState] = useState<'pending' | 'done' | 'error'>('pending')
  const [error, setError] = useState<string | null>(null)
  // 토큰은 한 번만 쓸 수 있으므로 StrictMode 의 이펙트 재실행에서 두 번 보내지 않는다
  const started = useRef(false)

  useEffect(() => {
    if (started.current) return
    started.current = true
    verifyEmail(params.get('token') ?? '')
      .then(() => setState('done'))
      .catch((e: unknown) => {
        setError(e instanceof ApiError ? e.message : '이메일 확인에 실패했습니다')
        setState('error')
      })
  }, [params])

  return (
    <section className="card auth" aria-label="이메일 확인">
      <h2>이메일 확인</h2>
      {state === 'pending' && <p>확인하는 중…</p>}
      {state === 'done' && <p>가입이 끝났습니다. 이제 로그인하세요.</p>}
      {state === 'error' && <p role="alert" className="error">{error}</p>}
      <Link to="/login">로그인 화면으로</Link>
    </section>
  )
}
