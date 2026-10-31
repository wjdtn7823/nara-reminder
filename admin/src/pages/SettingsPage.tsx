import { type FormEvent, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { fetchSettings, updateSettings } from '../api/settings'
import { CATEGORIES, CATEGORY_LABELS, type Category, type Settings } from '../types'

const toNumber = (text: string): number | null => (text.trim() === '' ? null : Number(text))

function SettingsForm({ initial }: { initial: Settings }) {
  const [keywords, setKeywords] = useState(initial.excludeKeywords)
  const [input, setInput] = useState('')
  const [categories, setCategories] = useState<Category[]>(initial.categories)
  const [min, setMin] = useState(initial.priceMin?.toString() ?? '')
  const [max, setMax] = useState(initial.priceMax?.toString() ?? '')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const addKeyword = () => {
    const value = input.trim()
    if (value && !keywords.some((k) => k.toLowerCase() === value.toLowerCase())) setKeywords([...keywords, value])
    setInput('')
  }

  const toggle = (category: Category) =>
    setCategories((prev) => (prev.includes(category) ? prev.filter((c) => c !== category) : [...prev, category]))

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    const priceMin = toNumber(min)
    const priceMax = toNumber(max)
    if (categories.length === 0) return setError('업무구분을 하나 이상 선택하세요')
    if (priceMin !== null && priceMax !== null && priceMin > priceMax) return setError('최소 가격이 최대 가격보다 클 수 없습니다')
    setError(null)
    try {
      await updateSettings({ excludeKeywords: keywords, categories: CATEGORIES.filter((c) => categories.includes(c)), priceMin, priceMax })
      setSaved(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '저장하지 못했습니다')
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      <section className="card" aria-label="제외 키워드">
        <h3>제외 키워드 <span className="count">{keywords.length}</span></h3>
        <p className="muted">공고명에 이 단어가 있으면 모든 항목에서 받지 않습니다.</p>
        <div className="row">
          <input aria-label="제외 키워드 입력" value={input} onChange={(e) => setInput(e.target.value)} placeholder="추가할 값" />
          <button type="button" onClick={addKeyword}>
            제외 키워드 추가
          </button>
        </div>
        <div className="tags">
          {keywords.map((keyword) => (
            <span key={keyword} className="tag">
              {keyword}
              <button type="button" aria-label={`${keyword} 삭제`} onClick={() => setKeywords(keywords.filter((k) => k !== keyword))}>
                ×
              </button>
            </span>
          ))}
        </div>
      </section>

      <section className="card" aria-label="업무구분 · 가격">
        <h3>업무구분 · 가격</h3>
        <fieldset className="row">
          <legend>업무구분</legend>
          {CATEGORIES.map((category) => (
            <label key={category}>
              <input type="checkbox" checked={categories.includes(category)} onChange={() => toggle(category)} />
              {CATEGORY_LABELS[category]}
            </label>
          ))}
        </fieldset>
        <div className="row">
          <label>
            최소 가격
            <input type="number" min={0} value={min} onChange={(e) => setMin(e.target.value)} />
          </label>
          <label>
            최대 가격
            <input type="number" min={0} value={max} onChange={(e) => setMax(e.target.value)} />
          </label>
        </div>
        <p className="muted">입찰공고는 추정가격, 사전규격은 배정예산 기준입니다. 가격이 없는 공고는 통과합니다.</p>
      </section>

      <button type="submit">저장</button>
      {error && <p role="alert" className="error">{error}</p>}
      {saved && <p role="status">저장했습니다. 다음 수집부터 적용됩니다</p>}
    </form>
  )
}

export function SettingsPage() {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchSettings()
      .then(setSettings)
      .catch(() => setError('공통 조건을 불러오지 못했습니다'))
  }, [])

  return (
    <section aria-label="공통 조건">
      <h2>공통 조건</h2>
      <p className="muted">모든 키워드·수요기관에 함께 적용됩니다.</p>
      {error && <p role="alert" className="error">{error}</p>}
      {settings && <SettingsForm initial={settings} />}
    </section>
  )
}
