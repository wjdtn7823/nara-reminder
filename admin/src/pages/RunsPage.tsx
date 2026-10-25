import { useEffect, useState } from 'react'
import { fetchRuns, type RunQuery } from '../api/runs'
import { formatDuration, formatKst } from '../format'
import { JOB_LABELS, type JobName, type JobRun, type Page, type RunStatus } from '../types'

const STATUS_LABELS: Record<RunStatus, string> = { SUCCESS: '성공', FAILURE: '실패' }

export function RunsPage() {
  const [query, setQuery] = useState<RunQuery>({ job: '', status: '', page: 0 })
  const [data, setData] = useState<Page<JobRun> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchRuns(query)
      .then((result) => {
        setData(result)
        setError(null)
      })
      .catch(() => setError('실행 이력을 불러오지 못했습니다'))
  }, [query])

  return (
    <section aria-label="실행 이력">
      <div className="row">
        <label>
          작업
          <select value={query.job} onChange={(e) => setQuery({ ...query, job: e.target.value as JobName | '', page: 0 })}>
            <option value="">전체</option>
            <option value="BID_POLLING">입찰공고</option>
            <option value="PRE_SPEC_DAILY">사전규격</option>
          </select>
        </label>
        <label>
          상태
          <select value={query.status} onChange={(e) => setQuery({ ...query, status: e.target.value as RunStatus | '', page: 0 })}>
            <option value="">전체</option>
            <option value="SUCCESS">성공</option>
            <option value="FAILURE">실패</option>
          </select>
        </label>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {data && data.items.length === 0 && <p>실행 이력이 없습니다</p>}
      {data && data.items.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>시작 (KST)</th><th>작업</th><th>조회 → 통과 → 발송</th><th>상태</th><th>소요</th><th>오류</th>
            </tr>
          </thead>
          <tbody>
            {data.items.map((run) => (
              <tr key={run.id}>
                <td>{formatKst(run.startedAt)}</td>
                <td>{JOB_LABELS[run.jobName]}</td>
                <td>{`${run.fetchedCount} → ${run.matchedCount} → ${run.sentCount}`}</td>
                <td><span className={`badge ${run.status.toLowerCase()}`}>{STATUS_LABELS[run.status]}</span></td>
                <td>{formatDuration(run.startedAt, run.finishedAt)}</td>
                <td>
                  {run.error && (
                    <details>
                      <summary>{run.error.length > 40 ? `${run.error.slice(0, 40)}…` : run.error}</summary>
                      <pre>{run.error}</pre>
                    </details>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && data.totalPages > 1 && (
        <div className="row">
          <button type="button" disabled={query.page === 0} onClick={() => setQuery({ ...query, page: query.page - 1 })}>이전</button>
          <span>{query.page + 1} / {data.totalPages}</span>
          <button type="button" disabled={query.page + 1 >= data.totalPages} onClick={() => setQuery({ ...query, page: query.page + 1 })}>다음</button>
        </div>
      )}
    </section>
  )
}
