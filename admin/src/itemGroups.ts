import { formatPrice } from './format'
import { CATEGORIES, CATEGORY_LABELS, type Item, type Settings } from './types'

export interface ItemGroup {
  cron: string
  nextSendAt: string | null
  items: Item[]
}

export function groupByCron(items: Item[]): ItemGroup[] {
  const groups = new Map<string, ItemGroup>()
  items.forEach((item) => {
    const group = groups.get(item.cron) ?? { cron: item.cron, nextSendAt: item.nextSendAt, items: [] }
    group.items.push(item)
    groups.set(item.cron, group)
  })
  const order = (group: ItemGroup): number => (group.nextSendAt ? Date.parse(group.nextSendAt) : Number.MAX_SAFE_INTEGER)
  return [...groups.values()].sort((a, b) => order(a) - order(b))
}

const priceRange = (min: number | null, max: number | null): string => {
  if (min === null && max === null) return '제한 없음'
  if (max === null) return `${formatPrice(min ?? 0)} 이상`
  if (min === null) return `${formatPrice(max)} 이하`
  return `${formatPrice(min)} ~ ${formatPrice(max)}`
}

export function summarizeSettings(settings: Settings): string {
  const categories = settings.categories.length === CATEGORIES.length
    ? '전체'
    : settings.categories.map((category) => CATEGORY_LABELS[category]).join('·')
  return `제외 키워드 ${settings.excludeKeywords.length}개 · 업무구분 ${categories} · 가격 ${priceRange(settings.priceMin, settings.priceMax)}`
}
