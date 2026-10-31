import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchItems } from '../api/items'
import { fetchSettings } from '../api/settings'
import { describeCron } from '../cadence'
import { formatKstShort } from '../format'
import { groupByCron, summarizeSettings } from '../itemGroups'
import type { Item, Settings } from '../types'

export function ItemsPage() {
  const [items, setItems] = useState<Item[] | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([fetchItems(), fetchSettings()])
      .then(([loadedItems, loadedSettings]) => {
        setItems(loadedItems)
        setSettings(loadedSettings)
      })
      .catch(() => setError('내 알림을 불러오지 못했습니다'))
  }, [])

  return (
    <section aria-label="내 알림">
      <div className="row between">
        <h2>
          내 알림 {items && <span className="count">{items.length}</span>}
        </h2>
        <Link className="button primary" to="/items/new">
          + 키워드 / 수요기관 추가
        </Link>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      {items && items.length === 0 && <p>아직 등록한 키워드·수요기관이 없습니다</p>}
      {items &&
        groupByCron(items).map((group) => {
          const title = describeCron(group.cron)
          return (
            <article key={group.cron} className="card" aria-label={title}>
              <div className="row between">
                <b>{title}</b>
                <span className="muted">
                  {group.nextSendAt ? `다음 발송 ${formatKstShort(group.nextSendAt)}` : '다음 발송 없음'} · {group.items.length}개
                </span>
              </div>
              <div className="tags">
                {group.items.map((item) => (
                  <Link key={item.id} className="tag" to={`/items/${item.id}`}>
                    {item.type === 'AGENCY' ? `🏛 ${item.value}` : item.value}
                  </Link>
                ))}
              </div>
            </article>
          )
        })}
      {settings && (
        <p className="muted">
          공통 조건: {summarizeSettings(settings)} <Link to="/settings">편집</Link>
        </p>
      )}
    </section>
  )
}
