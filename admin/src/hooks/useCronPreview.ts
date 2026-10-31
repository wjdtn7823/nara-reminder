import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import { previewCron } from '../api/cron'

interface CronPreviewState {
  next: string[]
  error: string | null
}

// 다음 발송 시각과 cron 검증은 worker 와 같은 계산을 쓰도록 서버에 맡긴다
export function useCronPreview(cron: string | null): CronPreviewState {
  const [state, setState] = useState<CronPreviewState>({ next: [], error: null })

  useEffect(() => {
    if (cron === null) return
    let active = true
    previewCron(cron)
      .then((preview) => {
        if (active) setState({ next: preview.next, error: null })
      })
      .catch((e: unknown) => {
        if (active) setState({ next: [], error: e instanceof ApiError ? e.message : '미리보기를 불러오지 못했습니다' })
      })
    return () => {
      active = false
    }
  }, [cron])

  return state
}
