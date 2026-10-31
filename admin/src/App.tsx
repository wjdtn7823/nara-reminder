import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider, RequireAuth } from './auth/AuthContext'
import { Header } from './components/Header'
import { ForgotPage } from './pages/ForgotPage'
import { ItemDetailPage } from './pages/ItemDetailPage'
import { ItemsPage } from './pages/ItemsPage'
import { LoginPage } from './pages/LoginPage'
import { NewItemPage } from './pages/NewItemPage'
import { ResetPage } from './pages/ResetPage'
import { RunsPage } from './pages/RunsPage'
import { SettingsPage } from './pages/SettingsPage'
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
            <Route path="/items" element={<RequireAuth><ItemsPage /></RequireAuth>} />
            <Route path="/items/new" element={<RequireAuth><NewItemPage /></RequireAuth>} />
            <Route path="/items/:id" element={<RequireAuth><ItemDetailPage /></RequireAuth>} />
            <Route path="/settings" element={<RequireAuth><SettingsPage /></RequireAuth>} />
            <Route path="/admin/runs" element={<RequireAuth admin><RunsPage /></RequireAuth>} />
            <Route path="*" element={<Navigate to="/items" replace />} />
          </Routes>
        </main>
      </AuthProvider>
    </BrowserRouter>
  )
}
