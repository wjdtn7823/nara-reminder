import { type FormEvent, useState } from 'react'
import { ApiError } from '../api/client'
import { updateSettings } from '../api/filters'
import { CATEGORIES, CATEGORY_LABELS, type Category, type Filters } from '../types'

interface SettingsCardProps {
  categories: Category[]
  priceMin: number | null
  priceMax: number | null
  onSaved: (filters: Filters) => void
}

const toNumber = (text: string): number | null => (text.trim() === '' ? null : Number(text))

export function SettingsCard({ categories, priceMin, priceMax, onSaved }: SettingsCardProps) {
  const [selected, setSelected] = useState<Category[]>(categories)
  const [min, setMin] = useState(priceMin?.toString() ?? '')
  const [max, setMax] = useState(priceMax?.toString() ?? '')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const toggle = (category: Category) =>
    setSelected((prev) => (prev.includes(category) ? prev.filter((c) => c !== category) : [...prev, category]))

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    const priceMinValue = toNumber(min)
    const priceMaxValue = toNumber(max)
    if (selected.length === 0) return setError('업무구분을 하나 이상 선택하세요')
    if (priceMinValue !== null && priceMaxValue !== null && priceMinValue > priceMaxValue) {
      return setError('최소 가격이 최대 가격보다 클 수 없습니다')
    }
    setError(null)
    try {
      const updated = await updateSettings({
        categories: CATEGORIES.filter((c) => selected.includes(c)),
        priceMin: priceMinValue,
        priceMax: priceMaxValue,
      })
      setSaved(true)
      onSaved(updated)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '저장에 실패했습니다')
    }
  }

  return (
    <section className="card" aria-label="업무구분 · 가격">
      <h2>업무구분 · 가격</h2>
      <form onSubmit={handleSubmit}>
        <fieldset className="row">
          <legend>업무구분</legend>
          {CATEGORIES.map((category) => (
            <label key={category}>
              <input type="checkbox" checked={selected.includes(category)} onChange={() => toggle(category)} />
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
        <button type="submit">저장</button>
      </form>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {saved && <p role="status">다음 수집(최대 5분)부터 적용됩니다</p>}
    </section>
  )
}
