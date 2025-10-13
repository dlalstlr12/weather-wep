type Props = { sky?: string | null; size?: number }

export default function WeatherIcon({ sky, size = 48 }: Props) {
  const s = (sky || '').toString()
  let emoji = '❓'
  if (s.includes('맑')) emoji = '☀️'
  else if (s.includes('구름')) emoji = '⛅'
  else if (s.includes('흐림')) emoji = '☁️'
  else if (s.includes('비')) emoji = '🌧️'
  else if (s.includes('눈')) emoji = '❄️'
  return (
    <span style={{ fontSize: size, lineHeight: 1 }}>{emoji}</span>
  )
}
