import { createContext, type ReactNode, useContext, useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { fetchMe } from '../api/auth'
import type { Me } from '../types'

interface AuthState {
  me: Me | null
  loading: boolean
  setMe: (me: Me | null) => void
}

const AuthContext = createContext<AuthState | null>(null)

// 첫 /api/me 호출이 로그인 여부 확인과 함께 XSRF-TOKEN 쿠키도 받아 온다
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetchMe()
      .then(setMe)
      .catch(() => setMe(null))
      .finally(() => setLoading(false))
  }, [])

  return <AuthContext.Provider value={{ me, loading, setMe }}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const state = useContext(AuthContext)
  if (!state) throw new Error('AuthProvider 안에서만 쓸 수 있습니다')
  return state
}

export function RequireAuth({ admin = false, children }: { admin?: boolean; children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <p>불러오는 중…</p>
  if (!me) return <Navigate to="/login" replace />
  if (admin && !me.admin) return <Navigate to="/items" replace />
  return <>{children}</>
}
