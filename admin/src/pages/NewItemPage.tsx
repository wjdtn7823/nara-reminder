import { type FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { createItem } from '../api/items'
import { IMMEDIATE_CRON } from '../cadence'
import { CadenceEditor } from '../components/CadenceEditor'
import type { ItemType } from '../types'

export function NewItemPage() {
  const navigate = useNavigate()
  const [type, setType] = useState<ItemType>('KEYWORD')
  const [value, setValue] = useState('')
  const [cron, setCron] = useState(IMMEDIATE_CRON)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    try {
      await createItem({ type, value, cron })
      navigate('/items')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '추가하지 못했습니다')
    }
  }

  return (
    <section className="card" aria-label="항목 추가">
      <h2>키워드 / 수요기관 추가</h2>
      <form onSubmit={handleSubmit}>
        <fieldset className="row">
          <legend>종류</legend>
          <label>
            <input type="radio" name="type" checked={type === 'KEYWORD'} onChange={() => setType('KEYWORD')} />
            키워드
          </label>
          <label>
            <input type="radio" name="type" checked={type === 'AGENCY'} onChange={() => setType('AGENCY')} />
            수요기관
          </label>
        </fieldset>
        <input aria-label="값" value={value} onChange={(e) => setValue(e.target.value)} placeholder={type === 'KEYWORD' ? '예: 타당성' : '예: 한국국제협력단'} />
        <p className="muted">
          {type === 'KEYWORD' ? '공고명' : '수요기관명'}에 이 단어가 포함되면 걸립니다 (대소문자 무시)
        </p>
        <h3>발송 주기</h3>
        <CadenceEditor value={cron} onChange={setCron} />
        <div className="row">
          <button type="submit">추가</button>
          <Link to="/items">취소</Link>
        </div>
      </form>
      {error && <p role="alert" className="error">{error}</p>}
    </section>
  )
}
