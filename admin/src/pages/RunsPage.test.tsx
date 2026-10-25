import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchRuns } from '../api/runs'
import type { JobRun, Page } from '../types'
import { RunsPage } from './RunsPage'

vi.mock('../api/runs')

const run: JobRun = {
  id: 1, jobName: 'BID_POLLING', startedAt: '2026-10-25T05:00:00Z', finishedAt: '2026-10-25T05:00:04Z',
  windowFrom: '2026-10-25T04:25:00Z', windowTo: '2026-10-25T05:00:00Z',
  fetchedCount: 40, matchedCount: 3, sentCount: 0, status: 'FAILURE', error: '메일 발송/기록 실패: SMTP 오류',
}
const page = (items: JobRun[], totalPages = 1): Page<JobRun> => ({ items, page: 0, size: 50, totalPages, totalElements: items.length })

describe('RunsPage', () => {
  beforeEach(() => vi.resetAllMocks())

  it('실행 이력을 KST 와 건수로 보여 준다', async () => {
    vi.mocked(fetchRuns).mockResolvedValue(page([run]))
    render(<RunsPage />)
    const row = await screen.findByRole('row', { name: /40 → 3 → 0/ })
    expect(within(row).getByText('입찰공고')).toBeInTheDocument()
    expect(within(row).getAllByText(/14:00/).length).toBeGreaterThan(0)
    expect(within(row).getByText('40 → 3 → 0')).toBeInTheDocument()
    expect(within(row).getByText('실패')).toBeInTheDocument()
    expect(within(row).getByText('4초')).toBeInTheDocument()
    expect(within(row).getAllByText(/SMTP 오류/).length).toBeGreaterThan(0)
  })

  it('작업을 바꾸면 첫 페이지부터 다시 조회한다', async () => {
    vi.mocked(fetchRuns).mockResolvedValue(page([run], 3))
    render(<RunsPage />)
    await screen.findByRole('row', { name: /40 → 3 → 0/ })
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    await userEvent.selectOptions(screen.getByLabelText('작업'), 'PRE_SPEC_DAILY')
    expect(fetchRuns).toHaveBeenLastCalledWith({ job: 'PRE_SPEC_DAILY', status: '', page: 0 })
  })

  it('이력이 없으면 안내를 보여 준다', async () => {
    vi.mocked(fetchRuns).mockResolvedValue(page([]))
    render(<RunsPage />)
    expect(await screen.findByText('실행 이력이 없습니다')).toBeInTheDocument()
  })
})
