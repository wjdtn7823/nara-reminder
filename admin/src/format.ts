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

export const formatPrice = (won: number): string => `${won.toLocaleString('ko-KR')}원`

// 서버의 LocalDateTime(시간대 없음, 이미 KST) 문자열을 그대로 줄여 보여 준다
export const formatLocal = (text: string): string => `${text.slice(5, 7)}/${text.slice(8, 10)} ${text.slice(11, 16)}`
