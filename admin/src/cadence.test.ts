import { describe, expect, it } from 'vitest'
import { buildCron, type CadenceChoice, describeCron, IMMEDIATE_CRON, parseCron } from './cadence'

const choice = (patch: Partial<CadenceChoice>): CadenceChoice => ({
  days: 'EVERYDAY', allDay: false, startHour: 9, endHour: 18, interval: 30, ...patch,
})

describe('buildCron', () => {
  it.each<[Partial<CadenceChoice>, string]>([
    [{ days: 'WEEKDAYS', interval: 30 }, '0 */30 9-17 * * MON-FRI'],
    [{ allDay: true, interval: 5 }, IMMEDIATE_CRON],
    [{ days: 'WEEKENDS', startHour: 9, endHour: 12, interval: 60 }, '0 0 9-11 * * SAT,SUN'],
    [{ interval: 120 }, '0 0 9-17/2 * * *'],
    [{ allDay: true, interval: 120 }, '0 0 */2 * * *'],
    [{ startHour: 9, endHour: 10, interval: 120 }, '0 0 9 * * *'],
    [{ days: 'WEEKDAYS', interval: 'DAILY' }, '0 0 9 * * MON-FRI'],
    [{ allDay: true, interval: 'DAILY' }, '0 0 0 * * *'],
  ])('%o → %s', (patch, cron) => {
    expect(buildCron(choice(patch))).toBe(cron)
  })
})

describe('parseCron', () => {
  it('편집기가 만든 식을 다시 선택값으로 읽는다', () => {
    expect(parseCron('0 */30 9-17 * * MON-FRI')).toEqual(choice({ days: 'WEEKDAYS', interval: 30 }))
    expect(parseCron(' 0  */5 * * * * ')).toMatchObject({ allDay: true, interval: 5 })
  })

  it('같은 식이 여러 선택으로 만들어지면 하루 1번으로 읽는다', () => {
    expect(parseCron('0 0 9 * * *')).toMatchObject({ interval: 'DAILY', startHour: 9 })
  })

  it('편집기로 만들 수 없는 식은 null', () => {
    expect(parseCron('0 15 10 * * *')).toBeNull()
    expect(parseCron('잘못된 식')).toBeNull()
  })

  it('만든 식은 모두 같은 식으로 되돌아온다', () => {
    const samples = [
      choice({}), choice({ allDay: true, interval: 60 }), choice({ days: 'WEEKENDS', startHour: 0, endHour: 24, interval: 5 }),
      choice({ startHour: 22, endHour: 24, interval: 120 }), choice({ days: 'WEEKDAYS', startHour: 18, interval: 'DAILY' }),
    ]
    samples.forEach((sample) => {
      const cron = buildCron(sample)
      const parsed = parseCron(cron)
      expect(parsed && buildCron(parsed)).toBe(cron)
    })
  })
})

describe('describeCron', () => {
  it.each([
    ['0 */30 9-17 * * MON-FRI', '평일 09:00~18:00 전까지, 30분마다'],
    [IMMEDIATE_CRON, '매일 하루 종일, 즉시(5분마다)'],
    ['0 0 9 * * *', '매일 09:00 하루 1번'],
    ['0 0 */2 * * SAT,SUN', '주말 하루 종일, 2시간마다'],
    ['0 15 10 * * *', '사용자 지정 (0 15 10 * * *)'],
  ])('%s → %s', (cron, text) => {
    expect(describeCron(cron)).toBe(text)
  })
})
