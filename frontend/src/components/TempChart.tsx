import { useEffect, useMemo, useRef, useState } from 'react'

export type ForecastItem = {
  dateTime: string
  temperature: number | null
  precipitation?: number | null
  sky?: string | null
  pop?: number | null
  reh?: number | null
  wsd?: number | null
}

export default function TempChart({ items }: { items: ForecastItem[] }) {
  const all = useMemo(() => (items || []).filter(it => it.temperature != null), [items])
  const [range, setRange] = useState(12)
  const [selected, setSelected] = useState<number | null>(null)
  const [hover, setHover] = useState<number | null>(null)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const [width, setWidth] = useState(720)

  useEffect(() => {
    if (!wrapRef.current) return
    const ro = new ResizeObserver((entries) => {
      for (const e of entries) {
        const w = e.contentRect.width
        if (w > 0) setWidth(Math.max(480, Math.min(1200, w)))
      }
    })
    ro.observe(wrapRef.current)
    return () => ro.disconnect()
  }, [])

  if (!all.length) return null

  const N = Math.min(all.length, range)
  const data = all.slice(0, N)
  const temps = data.map(d => d.temperature as number)
  const minT = Math.min(...temps)
  const maxT = Math.max(...temps)
  const padL = 40
  const padR = 12
  const padT = 12
  const padB = 24
  const height = 220

  const scaleX = (i: number) => padL + (i * (width - padL - padR)) / Math.max(1, N - 1)
  const scaleY = (t: number) => {
    if (maxT === minT) return height / 2
    const ratio = (t - minT) / (maxT - minT)
    return height - padB - ratio * (height - padT - padB)
  }

  const points = data.map((d, i) => ({ x: scaleX(i), y: scaleY(d.temperature as number) }))

  const pathD = (() => {
    if (points.length === 0) return ''
    const d: string[] = []
    for (let i = 0; i < points.length; i++) {
      const p = points[i]
      if (i === 0) d.push(`M ${p.x} ${p.y}`)
      else {
        const p0 = points[i - 1]
        const dx = (p.x - p0.x) / 2
        const c1x = p0.x + dx * 0.6
        const c1y = p0.y
        const c2x = p.x - dx * 0.6
        const c2y = p.y
        d.push(`C ${c1x} ${c1y}, ${c2x} ${c2y}, ${p.x} ${p.y}`)
      }
    }
    return d.join(' ')
  })()

  const lastX = points.length > 0 ? points[points.length - 1].x : padL
  const firstX = points.length > 0 ? points[0].x : padL
  const areaD = `${pathD} L ${lastX} ${height - padB} L ${firstX} ${height - padB} Z`

  const ticks = (() => {
    const arr: number[] = []
    const step = Math.max(1, Math.round((maxT - minT) / 4))
    for (let v = Math.floor(minT); v <= Math.ceil(maxT); v += step) arr.push(v)
    if (!arr.includes(Math.ceil(maxT))) arr.push(Math.ceil(maxT))
    return arr
  })()

  const fmtTime = (dt: string) => {
    if (!dt || dt.length < 10) return dt
    const hh = dt.slice(8, 10)
    return `${Number(hh)}시`
  }

  const fmtDetailTime = (dt: string) => {
    if (!dt || dt.length < 10) return dt
    const m = dt.slice(4, 6)
    const d = dt.slice(6, 8)
    const hh = dt.slice(8, 10)
    const mm = dt.slice(10, 12)
    return `${Number(m)}월 ${Number(d)}일 ${Number(hh)}시${Number(mm)}분`
  }

  return (
    <div className="card" style={{ padding: 16 }}>
      <div style={{ marginBottom: 10, display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ fontWeight: 700 }}>시간대별 기온</div>
        <div style={{ display: 'flex', gap: 6 }}>
          {[8, 12, 16, 24].map(r => (
            <button key={r} onClick={() => setRange(r)} style={{ background: range === r ? '#ef4444' : '#0ea5e9' }}>{r}</button>
          ))}
        </div>
      </div>
      <div ref={wrapRef} style={{ width: '100%', overflowX: 'hidden' }}>
        <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}
             onMouseLeave={() => setHover(null)}>
          <defs>
            <linearGradient id="tempArea" x1="0" x2="0" y1="0" y2="1">
              <stop offset="0%" stopColor="#ef4444" stopOpacity={0.35} />
              <stop offset="100%" stopColor="#ef4444" stopOpacity={0} />
            </linearGradient>
          </defs>
          <rect x={0} y={0} width={width} height={height} fill="transparent" />
          {ticks.map((t, i) => (
            <g key={i}>
              <line x1={padL} x2={width - padR} y1={scaleY(t)} y2={scaleY(t)} stroke="rgba(255,255,255,0.35)" strokeWidth={1} />
              <text x={8} y={scaleY(t) + 4} fontSize={11} fill="#0f172a">{t}°</text>
            </g>
          ))}
          <path d={areaD} fill="url(#tempArea)" stroke="none" />
          <path d={pathD} fill="none" stroke="#ef4444" strokeWidth={2.5} />
          {data.map((d, i) => {
            const cx = points[i].x
            const cy = points[i].y
            const active = selected === i || hover === i
            return (
              <g key={i}
                 onMouseEnter={() => setHover(i)}
                 onClick={() => setSelected(i)}
                 style={{ cursor: 'pointer' }}>
                <circle cx={cx} cy={cy} r={active ? 7 : 5} fill={active ? '#ef4444' : '#ffffff'} stroke="#ef4444" strokeWidth={2} />
                <text x={cx} y={height - 6} textAnchor="middle" fontSize={11} fill="#0f172a">{fmtTime(d.dateTime)}</text>
                {hover === i && (
                  <g transform={`translate(${cx + 8}, ${cy - 28})`}>
                    <rect x={-6} y={-16} rx={6} ry={6} width={80} height={28} fill="#ffffff" stroke="#ef4444" strokeWidth={1} />
                    <text x={34} y={2} textAnchor="middle" fontSize={12} fill="#0f172a">{d.temperature}°C</text>
                  </g>
                )}
              </g>
            )
          })}
        </svg>
      </div>

      {selected != null && data[selected] && (
        <div style={{ marginTop: 12, display: 'flex', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
          <div style={{ fontWeight: 700 }}>{fmtDetailTime(data[selected].dateTime)}</div>
          <div><span className="muted">기온</span> <b>{data[selected].temperature}°C</b></div>
          {data[selected].precipitation != null && <div><span className="muted">강수</span> <b>{data[selected].precipitation}mm</b></div>}
          {data[selected].pop != null && <div><span className="muted">강수확률</span> <b>{data[selected].pop}%</b></div>}
          {data[selected].reh != null && <div><span className="muted">습도</span> <b>{data[selected].reh}%</b></div>}
          {data[selected].wsd != null && <div><span className="muted">풍속</span> <b>{data[selected].wsd}m/s</b></div>}
          <div className="muted">{data[selected].sky || ''}</div>
        </div>
      )}
    </div>
  )
}
