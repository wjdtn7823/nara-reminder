import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from '../auth/AuthContext'

export interface RouteSpec {
  path: string
  element: ReactElement
}

export function renderRoutes(routes: RouteSpec[], initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>
        <Routes>
          {routes.map((route) => (
            <Route key={route.path} path={route.path} element={route.element} />
          ))}
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}
