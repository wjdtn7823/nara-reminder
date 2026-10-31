import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, RequireAuth } from './auth/AuthContext'
import { Header } from './components/Header'
import { FiltersPage } from './pages/FiltersPage'
import { ForgotPage } from './pages/ForgotPage'
import { LoginPage } from './pages/LoginPage'
import { ResetPage } from './pages/ResetPage'
import { RunsPage } from './pages/RunsPage'
import { SignupPage } from './pages/SignupPage'
import { VerifyPage } from './pages/VerifyPage'

export function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Header />
        <main>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/verify" element={<VerifyPage />} />
            <Route path="/forgot" element={<ForgotPage />} />
            <Route path="/reset" element={<ResetPage />} />
            <Route path="/filters" element={<RequireAuth><FiltersPage /></RequireAuth>} />
            <Route path="/runs" element={<RequireAuth admin><RunsPage /></RequireAuth>} />
            <Route path="*" element={<Navigate to="/filters" replace />} />
          </Routes>
        </main>
      </AuthProvider>
    </BrowserRouter>
  )
}
