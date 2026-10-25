import { useCallback, useEffect, useState } from 'react'
import { fetchFilters } from '../api/filters'
import { SettingsCard } from '../components/SettingsCard'
import { TermListCard } from '../components/TermListCard'
import type { Filters } from '../types'

export function FiltersPage() {
  const [filters, setFilters] = useState<Filters | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    fetchFilters()
      .then((data) => {
        setFilters(data)
        setError(null)
      })
      .catch(() => setError('필터를 불러오지 못했습니다'))
  }, [])

  useEffect(load, [load])

  if (error) return <p role="alert" className="error">{error}</p>
  if (!filters) return <p>불러오는 중…</p>

  return (
    <div className="grid">
      {filters.keywords.length === 0 && filters.agencies.length === 0 && (
        <p role="alert" className="warning">
          키워드와 수요기관이 모두 비어 있어 모든 공고가 메일로 발송됩니다.
        </p>
      )}
      <TermListCard title="키워드" type="KEYWORD" terms={filters.keywords} onChanged={load} />
      <TermListCard title="수요기관" type="AGENCY" terms={filters.agencies} onChanged={load} />
      <TermListCard title="제외 키워드" type="EXCLUDE_KEYWORD" terms={filters.excludeKeywords} onChanged={load} />
      <SettingsCard
        categories={filters.categories}
        priceMin={filters.priceMin}
        priceMax={filters.priceMax}
        onSaved={setFilters}
      />
    </div>
  )
}
