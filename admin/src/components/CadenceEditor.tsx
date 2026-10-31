import { useState } from 'react'
import { buildCron, type CadenceChoice, DEFAULT_CHOICE, type DaySet, describeCron, type Interval, parseCron } from '../cadence'
import { formatKstShort } from '../format'
import { useCronPreview } from '../hooks/useCronPreview'

interface CadenceEditorProps {
  value: string
  onChange: (cron: string) => void
}

const DAY_OPTIONS: { value: DaySet; label: string }[] = [
  { value: 'WEEKDAYS', label: '평일' },
  { value: 'WEEKENDS', label: '주말' },
  { value: 'EVERYDAY', label: '매일' },
]

const INTERVAL_OPTIONS: { value: Interval; label: string }[] = [
  { value: 5, label: '즉시 (5분마다)' },
  { value: 30, label: '30분마다' },
  { value: 60, label: '1시간마다' },
  { value: 120, label: '2시간마다' },
  { value: 'DAILY', label: '시작 시각에 하루 1번' },
]

const HOURS = Array.from({ length: 24 }, (_, hour) => hour)
const pad = (hour: number): string => `${String(hour).padStart(2, '0')}시`

export function CadenceEditor({ value, onChange }: CadenceEditorProps) {
  const parsed = parseCron(value)
  // 직접 만든 식이면 버튼은 기본값에서 다시 시작한다
  const choice = parsed ?? DEFAULT_CHOICE
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const preview = useCronPreview(value)

  const update = (patch: Partial<CadenceChoice>) => onChange(buildCron({ ...choice, ...patch }))

  return (
    <div className="cadence">
      <fieldset>
        <legend>① 요일</legend>
        <div className="row">
          {DAY_OPTIONS.map((option) => (
            <button key={option.value} type="button" aria-pressed={parsed?.days === option.value} onClick={() => update({ days: option.value })}>
              {option.label}
            </button>
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend>② 시간대</legend>
        <div className="row">
          <label>
            시작
            <select
              aria-label="시작 시각"
              value={choice.startHour}
              disabled={choice.allDay}
              onChange={(e) => {
                const startHour = Number(e.target.value)
                update({ startHour, endHour: Math.max(choice.endHour, startHour + 1) })
              }}
            >
              {HOURS.map((hour) => (
                <option key={hour} value={hour}>{pad(hour)}</option>
              ))}
            </select>
          </label>
          <label>
            끝
            <select
              aria-label="끝 시각"
              value={choice.endHour}
              disabled={choice.allDay || choice.interval === 'DAILY'}
              onChange={(e) => {
                const endHour = Number(e.target.value)
                update({ endHour, startHour: Math.min(choice.startHour, endHour - 1) })
              }}
            >
              {HOURS.map((hour) => (
                <option key={hour + 1} value={hour + 1}>{pad(hour + 1)}</option>
              ))}
            </select>
          </label>
          <label>
            <input type="checkbox" checked={parsed !== null && choice.allDay} onChange={(e) => update({ allDay: e.target.checked })} />
            하루 종일
          </label>
        </div>
      </fieldset>

      <fieldset>
        <legend>③ 주기</legend>
        <div className="row">
          {INTERVAL_OPTIONS.map((option) => (
            <button
              key={String(option.value)}
              type="button"
              aria-pressed={parsed?.interval === option.value}
              onClick={() => update({ interval: option.value })}
            >
              {option.label}
            </button>
          ))}
        </div>
      </fieldset>

      <div className="cadence-summary">
        <b>📬 {describeCron(value)}</b>
        {preview.next.length > 0 && <div className="muted">다음 발송: {preview.next.map(formatKstShort).join(' · ')}</div>}
        {preview.error && <p className="error">{preview.error}</p>}
        <div className="muted">
          cron <code>{value}</code>{' '}
          <button
            type="button"
            onClick={() => {
              setDraft(value)
              setEditing(true)
            }}
          >
            직접 편집
          </button>
        </div>
        {editing && (
          <div className="row">
            <input aria-label="cron 식" value={draft} onChange={(e) => setDraft(e.target.value)} />
            <button
              type="button"
              onClick={() => {
                onChange(draft.trim())
                setEditing(false)
              }}
            >
              적용
            </button>
            <button type="button" onClick={() => setEditing(false)}>
              취소
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
