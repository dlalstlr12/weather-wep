type ForecastItem = {
  dateTime: string
  temperature: number | null
  precipitation: number | null
  sky: string | null
  pop: number | null
  reh: number | null
  wsd: number | null
}

const fmtDate = (dt: string) => {
  if (!dt || dt.length < 8) return dt
  const m = Number(dt.slice(4, 6))
  const d = Number(dt.slice(6, 8))
  return `${m}월 ${d}일`
}

const fmtTime = (dt: string) => {
  if (!dt || dt.length < 10) return dt
  const hh = Number(dt.slice(8, 10))
  return `${hh}시`
}

import { useEffect, useRef } from 'react'

export default function ForecastList({ items, selectedDateTime }: { items: ForecastItem[]; selectedDateTime?: string }) {
  if (!items || items.length === 0) return null

  const elRefs = useRef<Record<string, HTMLDivElement | null>>({})

  const byDate: Record<string, ForecastItem[]> = {}
  for (const it of items) {
    const key = it.dateTime?.slice(0, 8) || 'unknown'
    if (!byDate[key]) byDate[key] = []
    byDate[key].push(it)
  }
  for (const k of Object.keys(byDate)) {
    byDate[k].sort((a, b) => (a.dateTime || '').localeCompare(b.dateTime || ''))
  }

  useEffect(() => {
    if (!selectedDateTime) return
    const el = elRefs.current[selectedDateTime]
    if (el) {
      el.scrollIntoView({ inline: 'center', behavior: 'smooth', block: 'nearest' })
    }
  }, [selectedDateTime])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {Object.entries(byDate).map(([date, arr]) => (
        <div key={date}>
          <div style={{ color: '#f8fafc', fontWeight: 700, marginBottom: 8 }}>{fmtDate(date)}</div>
          <div className="forecast-scroll" style={{ display: 'grid', gridAutoFlow: 'column', gridAutoColumns: 'minmax(140px, 1fr)', gap: 10, overflowX: 'auto', paddingBottom: 4 }}>
            {arr.map((it, idx) => {
              const sel = selectedDateTime && it.dateTime === selectedDateTime
              return (
                <div key={idx} className="card" ref={(el) => { elRefs.current[it.dateTime] = el }}
                     style={{ outline: sel ? '2px solid #ef4444' : 'none' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                    <div className="muted">{fmtTime(it.dateTime)}</div>
                    <div style={{ fontWeight: 700 }}>{it.temperature ?? '-'}°C</div>
                  </div>
                  <div style={{ display: 'flex', gap: 10, fontSize: 13 }}>
                    <div>강수 {it.precipitation ?? '-'}mm</div>
                    <div>{it.sky ?? '-'}</div>
                  </div>
                  <div style={{ display: 'flex', gap: 10, marginTop: 6, fontSize: 12 }}>
                    <div className="muted">강수확률 {it.pop ?? '-'}%</div>
                    <div className="muted">습도 {it.reh ?? '-'}%</div>
                    <div className="muted">풍속 {it.wsd ?? '-'}m/s</div>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      ))}
    </div>
  )
}
