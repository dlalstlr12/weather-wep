type ForecastItem = {
  dateTime: string
  temperature: number | null
}

type DayStat = { date: string; min: number | null; max: number | null }

function summarize(items: ForecastItem[]): DayStat[] {
  const byDate: Record<string, number[]> = {}
  for (const it of items) {
    if (!it?.dateTime || it.temperature == null) continue
    const d = it.dateTime.slice(0, 8)
    if (!byDate[d]) byDate[d] = []
    byDate[d].push(it.temperature)
  }
  return Object.entries(byDate)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, temps]) => ({
      date,
      min: temps.length ? Math.min(...temps) : null,
      max: temps.length ? Math.max(...temps) : null,
    }))
}

const fmtDate = (d: string) => `${Number(d.slice(4, 6))}월 ${Number(d.slice(6, 8))}일`

export default function DailySummary({ items }: { items: ForecastItem[] }) {
  const stats = summarize(items)
  if (!stats.length) return null
  return (
    <div className="daily-grid">
      {stats.slice(0, 5).map((s) => (
        <div key={s.date} className="card" style={{ padding: 14 }}>
          <div style={{ fontWeight: 700, marginBottom: 6 }}>{fmtDate(s.date)}</div>
          <div style={{ display: 'flex', gap: 12 }}>
            <div>
              <div className="muted">최저</div>
              <div style={{ fontWeight: 700 }}>{s.min ?? '-'}°C</div>
            </div>
            <div>
              <div className="muted">최고</div>
              <div style={{ fontWeight: 700 }}>{s.max ?? '-'}°C</div>
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}
