import { type FormEvent, useState } from 'react'
import { ApiError } from '../api/client'
import { addTerm, deleteTerm, updateTerm } from '../api/filters'
import type { Term, TermType } from '../types'

interface TermListCardProps {
  title: string
  type: TermType
  terms: Term[]
  onChanged: () => void
}

const SEARCH_THRESHOLD = 10

export function TermListCard({ title, type, terms, onChanged }: TermListCardProps) {
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editValue, setEditValue] = useState('')
  const [error, setError] = useState<string | null>(null)

  const run = async (action: () => Promise<unknown>): Promise<boolean> => {
    setError(null)
    try {
      await action()
      onChanged()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '요청에 실패했습니다')
      return false
    }
  }

  const handleAdd = async (event: FormEvent) => {
    event.preventDefault()
    if (await run(() => addTerm(type, input))) setInput('')
  }

  const handleSave = async (id: number) => {
    if (await run(() => updateTerm(id, editValue))) setEditingId(null)
  }

  const handleDelete = async (term: Term) => {
    if (window.confirm(`'${term.value}'을(를) 삭제할까요?`)) await run(() => deleteTerm(term.id))
  }

  const normalizedQuery = query.trim().toLowerCase()
  const visible = terms.filter((term) => term.value.toLowerCase().includes(normalizedQuery))

  return (
    <section className="card" aria-label={title}>
      <h2>
        {title} <span className="count">{terms.length}</span>
      </h2>
      <form className="row" onSubmit={handleAdd}>
        <input aria-label={`${title} 입력`} value={input} onChange={(e) => setInput(e.target.value)} placeholder="추가할 값" />
        <button type="submit">추가</button>
      </form>
      {terms.length > SEARCH_THRESHOLD && (
        <input aria-label={`${title} 검색`} value={query} onChange={(e) => setQuery(e.target.value)} placeholder="목록에서 검색" />
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <ul className="terms">
        {visible.map((term) => (
          <li key={term.id}>
            {editingId === term.id ? (
              <>
                <input aria-label={`${term.value} 수정`} value={editValue} onChange={(e) => setEditValue(e.target.value)} />
                <button type="button" onClick={() => handleSave(term.id)}>
                  저장
                </button>
                <button type="button" onClick={() => setEditingId(null)}>
                  취소
                </button>
              </>
            ) : (
              <>
                <span>{term.value}</span>
                <button
                  type="button"
                  onClick={() => {
                    setEditingId(term.id)
                    setEditValue(term.value)
                  }}
                >
                  수정
                </button>
                <button type="button" onClick={() => handleDelete(term)}>
                  삭제
                </button>
              </>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
