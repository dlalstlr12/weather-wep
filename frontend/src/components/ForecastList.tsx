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
  const m = dt.slice(4, 6)
  const d = dt.slice(6, 8)
  return `${m}/${d}`
}

const fmtTime = (dt: string) => {
  if (!dt || dt.length < 10) return dt
  const hh = dt.slice(8, 10)
  const mm = dt.slice(10, 12)
  return `${hh}:${mm}`
}

export default function ForecastList({ items }: { items: ForecastItem[] }) {
  if (!items || items.length === 0) return null

  const byDate: Record<string, ForecastItem[]> = {}
  for (const it of items) {
    const key = it.dateTime?.slice(0, 8) || 'unknown'
    if (!byDate[key]) byDate[key] = []
    byDate[key].push(it)
  }
  for (const k of Object.keys(byDate)) {
    byDate[k].sort((a, b) => (a.dateTime || '').localeCompare(b.dateTime || ''))
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {Object.entries(byDate).map(([date, arr]) => (
        <div key={date}>
          <div style={{ color: '#f8fafc', fontWeight: 700, marginBottom: 8 }}>{fmtDate(date)}</div>
          <div style={{ display: 'grid', gridAutoFlow: 'column', gridAutoColumns: 'minmax(140px, 1fr)', gap: 10, overflowX: 'auto', paddingBottom: 4 }}>
            {arr.map((it, idx) => (
              <div key={idx} className="card">
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
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
