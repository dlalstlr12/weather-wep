import { useEffect, useState } from 'react'
import AppLogo from './AppLogo'

type Props = {
  align?: 'center' | 'left'
  size?: number
}

export default function BrandBar({ align = 'center', size = 48 }: Props) {
  const [LucideIcon, setLucideIcon] = useState<null | ((props: any) => JSX.Element)>(null)

  useEffect(() => {
    let mounted = true
    import('lucide-react')
      .then((m: any) => {
        if (!mounted) return
        // Prefer CloudSun, fallback to Sun
        const Icon = m.CloudSun || m.Sun || null
        if (Icon) setLucideIcon(() => Icon)
      })
      .catch(() => {})
    return () => { mounted = false }
  }, [])

  const outerStyle: React.CSSProperties = {
    display: 'flex', justifyContent: align === 'center' ? 'center' : 'flex-start'
  }
  const barStyle: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: 10,
    padding: '8px 14px', borderRadius: 9999,
    background: 'rgba(15, 23, 42, 0.55)',
    border: '1px solid rgba(148, 163, 184, 0.25)',
    boxShadow: '0 4px 16px rgba(0,0,0,0.25)',
    backdropFilter: 'blur(6px)'
  }
  const titleStyle: React.CSSProperties = {
    fontSize: 'clamp(18px, 2.8vw, 30px)',
    fontWeight: 900,
    letterSpacing: 0.4,
    color: '#f8fafc',
    textShadow: '0 1px 2px rgba(0,0,0,0.5)'
  }
  const iconColor = '#fbbf24' // amber-400

  return (
    <div style={outerStyle}>
      <div style={barStyle}>
        {LucideIcon ? (
          <LucideIcon size={size} color={iconColor} strokeWidth={2.2} />
        ) : (
          <AppLogo size={size} />
        )}
        <div style={titleStyle}>오늘 날씨 어때?</div>
      </div>
    </div>
  )
}
