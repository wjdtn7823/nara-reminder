const kst = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export const formatKst = (iso: string): string => kst.format(new Date(iso))

export const formatDuration = (from: string, to: string): string =>
  `${Math.max(0, Math.round((Date.parse(to) - Date.parse(from)) / 1000))}초`

const shortKst = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  month: 'numeric',
  day: 'numeric',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export const formatKstShort = (iso: string): string => shortKst.format(new Date(iso))
