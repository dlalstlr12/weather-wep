import { useEffect, useState } from 'react'
import { api } from '../api/client'
import ForecastList from '../components/ForecastList'
import WeatherIcon from '../components/WeatherIcon'
import DailySummary from '../components/DailySummary'
import TempChart from '../components/TempChart'

type Coords = { lat: number; lon: number }

export default function MainPage() {
  const [coords, setCoords] = useState<Coords | null>(null)
  const [city, setCity] = useState('')
  const [weather, setWeather] = useState<any>(null)
  const [forecast, setForecast] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [locationLabel, setLocationLabel] = useState('')

  useEffect(() => {
    if (!navigator.geolocation) return
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setCoords({ lat: pos.coords.latitude, lon: pos.coords.longitude })
        setLocationLabel('현재 위치')
      },
      () => {}
    )
  }, [])

  useEffect(() => {
    const fetchWeather = async () => {
      if (!coords) return
      try {
        setLoading(true)
        setError('')
        const [cur, fc] = await Promise.all([
          api(`/api/weather/current?lat=${coords.lat}&lon=${coords.lon}`),
          api(`/api/weather/forecast?lat=${coords.lat}&lon=${coords.lon}`)
        ])
        setWeather(cur)
        setForecast(fc?.items || [])
      } catch (e) {
        setError('날씨 정보를 불러오지 못했습니다.')
      } finally {
        setLoading(false)
      }
    }
    fetchWeather()
  }, [coords])

  const onSearch = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!city.trim()) return
    setError('')
    setLoading(true)
    try {
      // 추후 도시명 → 좌표 변환 API 연동 예정. 임시로 백엔드에 쿼리 전달 형식 유지.
      const [cur, fc] = await Promise.all([
        api(`/api/weather/current?city=${encodeURIComponent(city.trim())}`),
        api(`/api/weather/forecast?city=${encodeURIComponent(city.trim())}`)
      ])
      setWeather(cur)
      setForecast(fc?.items || [])
      setLocationLabel(city.trim())
    } catch (e) {
      setError('도시의 날씨 정보를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="container">
      <header className="header">
        <div className="title">
          <WeatherIcon sky={weather?.sky || '맑음'} size={28} />
          <div>
            <div style={{ fontSize: 20 }}>오늘의 날씨</div>
            <div className="subtitle">{locationLabel ? `${locationLabel} · ` : ''}현재 조건과 3시간 간격 예보</div>
          </div>
        </div>
      </header>

      <section className="section">
        <form onSubmit={onSearch} className="search">
          <input
            value={city}
            onChange={(e) => setCity(e.target.value)}
            placeholder="도시명을 입력하세요"
          />
          <button type="submit">검색</button>
        </form>
      </section>

      <section className="content">
        {loading && <p>불러오는 중...</p>}
        {error && <p className="error">{error}</p>}
        {weather && (
          <div className="card">
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <WeatherIcon sky={weather.sky} />
              <div>
                <div style={{ fontSize: 28, fontWeight: 700 }}>{weather.temperature ?? '-'}°C</div>
                <div className="muted">{weather.sky ?? '-'}</div>
              </div>
              <div style={{ marginLeft: 'auto', textAlign: 'right' }}>
                <div className="muted">강수</div>
                <div style={{ fontWeight: 600 }}>{weather.precipitation ?? '-'} mm</div>
              </div>
            </div>
          </div>
        )}
        {forecast && forecast.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <h2 style={{ color: '#f8fafc' }}>일일 요약</h2>
            <DailySummary items={forecast} />
          </div>
        )}
        {forecast && forecast.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <TempChart items={forecast} />
          </div>
        )}
        {forecast && forecast.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <h2>예보</h2>
            <ForecastList items={forecast} />
          </div>
        )}
      </section>
    </div>
  )
}
