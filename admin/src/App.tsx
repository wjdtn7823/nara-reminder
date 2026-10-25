import { BrowserRouter, Navigate, NavLink, Route, Routes } from 'react-router-dom'
import { FiltersPage } from './pages/FiltersPage'
import { RunsPage } from './pages/RunsPage'

export function App() {
  return (
    <BrowserRouter>
      <header className="header">
        <h1>나라장터 알림 어드민</h1>
        <nav>
          <NavLink to="/filters">필터</NavLink>
          <NavLink to="/runs">실행 이력</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/filters" replace />} />
          <Route path="/filters" element={<FiltersPage />} />
          <Route path="/runs" element={<RunsPage />} />
          <Route path="*" element={<Navigate to="/filters" replace />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}
