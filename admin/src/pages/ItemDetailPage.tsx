import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { deleteItem, fetchItem, updateItem } from '../api/items'
import { describeCron } from '../cadence'
import { CadenceEditor } from '../components/CadenceEditor'
import { formatKstShort, formatLocal, formatPrice } from '../format'
import { useCronPreview } from '../hooks/useCronPreview'
import { ITEM_TYPE_LABELS, type ItemDetail, type RecentNotice, SOURCE_LABELS } from '../types'

function StatusCell({ notice }: { notice: RecentNotice }) {
  if (notice.status === 'SENT') return <>✅ 발송 {notice.sentAt && <span className="muted">{formatKstShort(notice.sentAt)}</span>}</>
  if (notice.status === 'FAILED') return <>❌ 실패</>
  return <>⏳ 대기 {notice.scheduledAt && <span className="muted">{formatKstShort(notice.scheduledAt)} 예정</span>}</>
}

export function ItemDetailPage() {
  const { id } = useParams()
  const itemId = Number(id)
  const navigate = useNavigate()
  const [detail, setDetail] = useState<ItemDetail | null>(null)
  const [value, setValue] = useState('')
  const [editingCron, setEditingCron] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const preview = useCronPreview(detail?.item.cron ?? null)

  const load = useCallback(
    () =>
      fetchItem(itemId)
        .then((loaded) => {
          setDetail(loaded)
          setValue(loaded.item.value)
        })
        .catch(() => setError('항목을 불러오지 못했습니다')),
    [itemId],
  )

  useEffect(() => {
    void load()
  }, [load])

  const run = async (action: () => Promise<unknown>, done: () => void) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      done()
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '저장하지 못했습니다')
    }
  }

  const handleValueSave = (event: FormEvent) => {
    event.preventDefault()
    void run(() => updateItem(itemId, { value }), () => setMessage('저장했습니다'))
  }

  const handleDelete = async () => {
    if (!detail || !window.confirm(`'${detail.item.value}'을(를) 삭제할까요? 대기 중인 공고도 더 이상 보내지 않습니다.`)) return
    try {
      await deleteItem(itemId)
      navigate('/items')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '삭제하지 못했습니다')
    }
  }

  if (!detail) return error ? <p role="alert" className="error">{error}</p> : <p>불러오는 중…</p>
  const { item, stats, recent } = detail
  const typeLabel = ITEM_TYPE_LABELS[item.type]

  return (
    <section aria-label="항목 상세">
      <Link to="/items">← 내 알림</Link>
      <div className="row between">
        <h2>
          <span className="badge">{item.type === 'KEYWORD' ? '🔑' : '🏛'} {typeLabel}</span> {item.value}
        </h2>
        <button type="button" className="danger" onClick={handleDelete}>
          삭제
        </button>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {message && <p role="status">{message}</p>}

      <div className="grid">
        <section className="card" aria-label="설정">
          <h3>설정</h3>
          <form className="row" onSubmit={handleValueSave}>
            <label>
              {typeLabel}
              <input value={value} onChange={(e) => setValue(e.target.value)} />
            </label>
            <button type="submit">값 저장</button>
          </form>
          <p className="muted">
            {item.type === 'KEYWORD' ? '공고명' : '수요기관명'}에 이 단어가 포함되면 걸립니다 (대소문자 무시). 공통 조건도 함께 적용됩니다.{' '}
            <Link to="/settings">공통 조건 편집</Link>
          </p>
          <h3>발송 주기</h3>
          {editingCron === null ? (
            <div className="cadence-summary">
              <b>🏢 {describeCron(item.cron)}</b>
              {preview.next.length > 0 && <div className="muted">다음 발송 {preview.next.map(formatKstShort).join(' · ')}</div>}
              <div className="muted">cron <code>{item.cron}</code></div>
              <button type="button" onClick={() => setEditingCron(item.cron)}>
                변경
              </button>
            </div>
          ) : (
            <>
              <CadenceEditor value={editingCron} onChange={setEditingCron} />
              <p className="muted">저장하면 대기 중인 공고에도 바로 적용됩니다.</p>
              <div className="row">
                <button type="button" onClick={() => void run(() => updateItem(itemId, { cron: editingCron }), () => setEditingCron(null))}>
                  주기 저장
                </button>
                <button type="button" onClick={() => setEditingCron(null)}>
                  취소
                </button>
              </div>
            </>
          )}
        </section>

        <section className="card" aria-label="최근 7일">
          <h3>최근 7일</h3>
          <div className="row stats">
            <div><b>{stats.matched}</b><span className="muted">걸린 공고</span></div>
            <div><b>{stats.sent}</b><span className="muted">발송</span></div>
            <div><b>{stats.pending}</b><span className="muted">대기</span></div>
          </div>
          <p className="muted">등록 {formatKstShort(item.createdAt)} · 다른 항목과 같이 걸린 공고는 더 빠른 주기로 1번만 발송됩니다.</p>
        </section>
      </div>

      <section className="card" aria-label="최근 걸린 공고">
        <h3>최근 걸린 공고 ({recent.length}건)</h3>
        {recent.length === 0 ? (
          <p className="muted">아직 걸린 공고가 없습니다</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>걸린 시각</th><th>상태</th><th>구분</th><th>공고명</th><th>수요기관</th><th>가격</th><th>마감</th>
              </tr>
            </thead>
            <tbody>
              {recent.map((notice) => {
                const title = `${notice.revision ? '[정정] ' : ''}${notice.title}`
                return (
                  <tr key={notice.id}>
                    <td>{formatKstShort(notice.matchedAt)}</td>
                    <td><StatusCell notice={notice} /></td>
                    <td>{SOURCE_LABELS[notice.source]}</td>
                    <td>{notice.detailUrl ? <a href={notice.detailUrl} target="_blank" rel="noreferrer">{title}</a> : title}</td>
                    <td>{notice.agency ?? '-'}</td>
                    <td>{notice.price === null ? '-' : formatPrice(notice.price)}</td>
                    <td>{notice.deadlineAt ? formatLocal(notice.deadlineAt) : '-'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </section>
    </section>
  )
}
