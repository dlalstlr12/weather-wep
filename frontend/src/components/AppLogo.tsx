
type Props = {
  size?: number
}

// 간단한 해+구름 아이콘 SVG
export default function AppLogo({ size = 48 }: Props) {
  const s = size
  return (
    <svg width={s} height={s} viewBox="0 0 64 64" fill="none" xmlns="http://www.w3.org/2000/svg" aria-label="오늘 날씨 어때? 로고">
      <defs>
        <linearGradient id="sunGrad" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#FDB813"/>
          <stop offset="100%" stopColor="#FF8C00"/>
        </linearGradient>
      </defs>
      {/* Sun */}
      <circle cx="24" cy="24" r="10" fill="url(#sunGrad)" />
      {/* Sun rays */}
      <g stroke="#FDB813" strokeWidth="2" strokeLinecap="round">
        <line x1="24" y1="7" x2="24" y2="3"/>
        <line x1="24" y1="41" x2="24" y2="45"/>
        <line x1="7" y1="24" x2="3" y2="24"/>
        <line x1="41" y1="24" x2="45" y2="24"/>
        <line x1="12" y1="12" x2="9" y2="9"/>
        <line x1="36" y1="36" x2="39" y2="39"/>
        <line x1="12" y1="36" x2="9" y2="39"/>
        <line x1="36" y1="12" x2="39" y2="9"/>
      </g>
      {/* Cloud */}
      <g>
        <circle cx="38" cy="36" r="10" fill="#E5E7EB"/>
        <circle cx="48" cy="38" r="8" fill="#E5E7EB"/>
        <rect x="28" y="38" width="28" height="10" rx="5" fill="#E5E7EB"/>
        <circle cx="32" cy="40" r="6" fill="#E5E7EB"/>
      </g>
    </svg>
  )
}
