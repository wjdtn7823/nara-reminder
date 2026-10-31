export type DaySet = 'WEEKDAYS' | 'WEEKENDS' | 'EVERYDAY'
export type Interval = 5 | 30 | 60 | 120 | 'DAILY'

// 시간대는 정시 단위다. endHour 는 그 시각 "전까지"를 뜻한다 (09~18 → 17시대까지 발송)
export interface CadenceChoice {
  days: DaySet
  allDay: boolean
  startHour: number
  endHour: number
  interval: Interval
}

export const IMMEDIATE_CRON = '0 */5 * * * *'
export const DEFAULT_CHOICE: CadenceChoice = { days: 'EVERYDAY', allDay: true, startHour: 9, endHour: 18, interval: 5 }

const DOW: Record<DaySet, string> = { WEEKDAYS: 'MON-FRI', WEEKENDS: 'SAT,SUN', EVERYDAY: '*' }
const DAY_LABELS: Record<DaySet, string> = { WEEKDAYS: '평일', WEEKENDS: '주말', EVERYDAY: '매일' }
const INTERVAL_LABELS: Record<Exclude<Interval, 'DAILY'>, string> = {
  5: '즉시(5분마다)', 30: '30분마다', 60: '1시간마다', 120: '2시간마다',
}

const hourRange = (c: CadenceChoice): string =>
  c.endHour - 1 === c.startHour ? `${c.startHour}` : `${c.startHour}-${c.endHour - 1}`

export function buildCron(c: CadenceChoice): string {
  const dow = DOW[c.days]
  switch (c.interval) {
    case 5:
    case 30:
      return `0 */${c.interval} ${c.allDay ? '*' : hourRange(c)} * * ${dow}`
    case 60:
      return `0 0 ${c.allDay ? '*' : hourRange(c)} * * ${dow}`
    case 120: {
      // 한 시간짜리 구간에 /2 를 붙이면 "그 시각부터 2시간마다"가 되어 밤까지 이어지므로 붙이지 않는다
      const single = c.endHour - 1 === c.startHour
      const hours = c.allDay ? '*/2' : single ? `${c.startHour}` : `${hourRange(c)}/2`
      return `0 0 ${hours} * * ${dow}`
    }
    case 'DAILY':
      return `0 0 ${c.allDay ? 0 : c.startHour} * * ${dow}`
  }
}

// 편집기로 만들 수 있는 모든 선택을 미리 만들어 cron → 선택 표를 둔다. 같은 식은 먼저 나온 선택(하루 1번 우선)을 쓴다
const DAYS: DaySet[] = ['EVERYDAY', 'WEEKDAYS', 'WEEKENDS']
const INTERVALS: Interval[] = ['DAILY', 5, 30, 60, 120]
const BY_CRON = new Map<string, CadenceChoice>()
for (const days of DAYS) {
  for (const interval of INTERVALS) {
    const candidates: CadenceChoice[] = [{ ...DEFAULT_CHOICE, days, interval, allDay: true }]
    for (let startHour = 0; startHour < 24; startHour++) {
      for (let endHour = startHour + 1; endHour <= 24; endHour++) {
        candidates.push({ days, allDay: false, startHour, endHour, interval })
      }
    }
    candidates.forEach((candidate) => {
      const cron = buildCron(candidate)
      if (!BY_CRON.has(cron)) BY_CRON.set(cron, candidate)
    })
  }
}

export const parseCron = (cron: string): CadenceChoice | null => BY_CRON.get(cron.trim().replace(/\s+/g, ' ')) ?? null

const pad = (hour: number): string => `${String(hour).padStart(2, '0')}:00`

export function describeChoice(c: CadenceChoice): string {
  const day = DAY_LABELS[c.days]
  if (c.interval === 'DAILY') return `${day} ${pad(c.allDay ? 0 : c.startHour)} 하루 1번`
  const range = c.allDay ? '하루 종일' : `${pad(c.startHour)}~${pad(c.endHour)} 전까지`
  return `${day} ${range}, ${INTERVAL_LABELS[c.interval]}`
}

export function describeCron(cron: string): string {
  const parsed = parseCron(cron)
  return parsed ? describeChoice(parsed) : `사용자 지정 (${cron})`
}
