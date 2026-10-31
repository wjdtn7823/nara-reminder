import { NavLink, useNavigate } from 'react-router-dom'
import { logout } from '../api/auth'
import { useAuth } from '../auth/AuthContext'

export function Header() {
  const { me, setMe } = useAuth()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout().catch(() => undefined)
    setMe(null)
    navigate('/login')
  }

  return (
    <header className="header">
      <h1>나라장터 알리미</h1>
      {me && (
        <nav>
          <NavLink to="/items">내 알림</NavLink>
          <NavLink to="/settings">공통 조건</NavLink>
          {me.admin && (
            <>
              <span className="badge admin">관리자</span>
              <NavLink to="/admin/invites">초대 코드</NavLink>
              <NavLink to="/admin/runs">실행 이력</NavLink>
            </>
          )}
          <span className="me">{me.email}</span>
          <button type="button" onClick={handleLogout}>
            로그아웃
          </button>
        </nav>
      )}
    </header>
  )
}
