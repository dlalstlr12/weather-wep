type ForecastItem = {
  dateTime: string
  temperature: number | null
  precipitation: number | null
  sky: string | null
  pop: number | null
  reh: number | null
  wsd: number | null
}

export default function ForecastList({ items }: { items: ForecastItem[] }) {
  if (!items || items.length === 0) return null

  const fmt = (dt: string) => {
    if (!dt || dt.length < 10) return dt
    const y = dt.slice(0, 4)
    const m = dt.slice(4, 6)
    const d = dt.slice(6, 8)
    const hh = dt.slice(8, 10)
    const mm = dt.slice(10, 12)
    return `${m}/${d} ${hh}:${mm}`
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 12 }}>
      {items.slice(0, 8).map((it, idx) => (
        <div key={idx} style={{ border: '1px solid #e5e5e5', borderRadius: 8, padding: 12 }}>
          <div style={{ fontSize: 12, color: '#666' }}>{fmt(it.dateTime)}</div>
          <div style={{ fontWeight: 600, marginTop: 6 }}>{it.temperature ?? '-'}°C</div>
          <div style={{ fontSize: 13 }}>강수 {it.precipitation ?? '-'}mm</div>
          <div style={{ fontSize: 13 }}>하늘 {it.sky ?? '-'}</div>
          <div style={{ fontSize: 12, color: '#666' }}>강수확률 {it.pop ?? '-'}%</div>
          <div style={{ fontSize: 12, color: '#666' }}>습도 {it.reh ?? '-'}%</div>
          <div style={{ fontSize: 12, color: '#666' }}>풍속 {it.wsd ?? '-'}m/s</div>
        </div>
      ))}
    </div>
  )
}
