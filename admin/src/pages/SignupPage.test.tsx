import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { fetchMe, signup } from '../api/auth'
import { renderRoutes } from '../test/renderRoutes'
import { SignupPage } from './SignupPage'

vi.mock('../api/auth')

describe('SignupPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(fetchMe).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', '로그인 필요'))
  })

  const fill = async (password: string, confirm: string) => {
    await userEvent.type(screen.getByLabelText('이메일'), 'new@example.com')
    await userEvent.type(screen.getByLabelText('비밀번호'), password)
    await userEvent.type(screen.getByLabelText('비밀번호 확인'), confirm)
    await userEvent.click(screen.getByRole('button', { name: '가입 요청' }))
  }

  it('주소의 code 로 초대 코드를 채우고, 가입 요청하면 확인 메일 안내를 보여 준다', async () => {
    vi.mocked(signup).mockResolvedValue(undefined)
    renderRoutes([{ path: '/signup', element: <SignupPage /> }], '/signup?code=ABCD-2345')

    expect(screen.getByLabelText('초대 코드')).toHaveValue('ABCD-2345')
    await fill('correct-horse', 'correct-horse')

    expect(signup).toHaveBeenCalledWith({ inviteCode: 'ABCD-2345', email: 'new@example.com', password: 'correct-horse' })
    expect(await screen.findByRole('status')).toHaveTextContent('확인 메일을 보냈습니다')
  })

  it('비밀번호 확인이 다르면 요청하지 않는다', async () => {
    renderRoutes([{ path: '/signup', element: <SignupPage /> }], '/signup')

    await userEvent.type(screen.getByLabelText('초대 코드'), 'ABCD-2345')
    await fill('correct-horse', 'different')

    expect(signup).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveTextContent('비밀번호 확인이 일치하지 않습니다')
  })
})
