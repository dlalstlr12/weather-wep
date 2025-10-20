import { useEffect, useState } from 'react'
import { api } from '../api/client'
import ForecastList from '../components/ForecastList'
import WeatherIcon from '../components/WeatherIcon'
import DailySummary from '../components/DailySummary'
import TempChart from '../components/TempChart'
import { isAuthenticated, getUserName } from '../auth'
import { consumeFlash } from '../flash'
import { useNavigate } from 'react-router-dom'
import KakaoMap from '../components/KakaoMap'

type Coords = { lat: number; lon: number }

export default function MainPage() {
  const nav = useNavigate()
  const [coords, setCoords] = useState<Coords | null>(null)
  const [mapCenter, setMapCenter] = useState<Coords | null>(null)
  const [city, setCity] = useState('')
  const [weather, setWeather] = useState<any>(null)
  const [forecast, setForecast] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [locationLabel, setLocationLabel] = useState('')
  const [selectedDateTime, setSelectedDateTime] = useState<string | null>(null)
  const [favorites, setFavorites] = useState<string[]>([])
  const [userName, setUserName] = useState<string | null>(getUserName())
  const [notice, setNotice] = useState<string>('')
  const [placeQuery, setPlaceQuery] = useState('')
  const [placeResults, setPlaceResults] = useState<Array<{ name: string; address: string; lat: number; lon: number }>>([])
  const [selectedPlace, setSelectedPlace] = useState<{ name: string; lat: number; lon: number } | null>(null)
  const [placesSearchKey, setPlacesSearchKey] = useState<string | null>(null)
  const [placesDebouncedKey, setPlacesDebouncedKey] = useState<string | null>(null)
  const [showPlaceResults, setShowPlaceResults] = useState(false)

  useEffect(() => {
    const saved = localStorage.getItem('favCities')
    if (saved) {
      try { setFavorites(JSON.parse(saved)) } catch {}
    }
    const msg = consumeFlash()
    if (msg) setNotice(msg)
    if (!navigator.geolocation) return
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setCoords({ lat: pos.coords.latitude, lon: pos.coords.longitude })
        setLocationLabel('현재 위치')
      },
      () => {}
    )
  }, [])

  const onLogin = () => {
    nav('/login')
  }
  const onLogout = () => {
    localStorage.removeItem('accessToken')
    setUserName(null)
    setNotice('로그아웃되었습니다.')
  }

  const onChat = () => {
    if (!isAuthenticated()) {
      setNotice('로그인 후 이용해주세요')
      return
    }
    nav('/chat')
  }

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
        setSelectedDateTime(null)
      } catch (e) {
        setError('날씨 정보를 불러오지 못했습니다.')
      } finally {
        setLoading(false)
      }
    }
    fetchWeather()
  }, [coords])

  // Debounce for Kakao places search (only after submit)
  useEffect(() => {
    if (!showPlaceResults || !placesSearchKey || !placesSearchKey.trim()) {
      setPlacesDebouncedKey(null)
      return
    }
    const t = setTimeout(() => setPlacesDebouncedKey(placesSearchKey.trim()), 300)
    return () => clearTimeout(t)
  }, [placesSearchKey, showPlaceResults])

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
      // 검색 기반으로 지도는 도시 지오코딩을 사용하도록 좌표를 비웁니다.
      setCoords(null)
      setMapCenter(null)
      setSelectedPlace(null)
      // 장소 검색 상태 초기화
      setShowPlaceResults(false)
      setPlaceResults([])
      setPlacesSearchKey(null)
      setPlacesDebouncedKey(null)
      setSelectedDateTime(null)
    } catch (e) {
      setError('도시의 날씨 정보를 불러오지 못했습니다.')
    } finally {
      setLoading(false)
    }
  }

  const onRefresh = async () => {
    if (loading) return
    setError('')
    setLoading(true)
    try {
      if (locationLabel && locationLabel !== '현재 위치') {
        const q = encodeURIComponent(locationLabel)
        const [cur, fc] = await Promise.all([
          api(`/api/weather/current?city=${q}&nocache=true`),
          api(`/api/weather/forecast?city=${q}&nocache=true`)
        ])
        setWeather(cur); setForecast(fc?.items || [])
      } else if (coords) {
        const [cur, fc] = await Promise.all([
          api(`/api/weather/current?lat=${coords.lat}&lon=${coords.lon}&nocache=true`),
          api(`/api/weather/forecast?lat=${coords.lat}&lon=${coords.lon}&nocache=true`)
        ])
        setWeather(cur); setForecast(fc?.items || [])
      }
      setSelectedDateTime(null)
    } catch (e) {
      setError('새로고침에 실패했습니다.')
    } finally { setLoading(false) }
  }

  const addFavorite = () => {
    const name = locationLabel && locationLabel !== '현재 위치' ? locationLabel : city.trim()
    if (!name) return
    if (favorites.includes(name)) return
    const next = [...favorites, name].slice(0, 8)
    setFavorites(next)
    localStorage.setItem('favCities', JSON.stringify(next))
  }

  const selectFavorite = async (name: string) => {
    setCity(name)
    setLocationLabel(name)
    setError('')
    setLoading(true)
    try {
      const q = encodeURIComponent(name)
      const [cur, fc] = await Promise.all([
        api(`/api/weather/current?city=${q}`),
        api(`/api/weather/forecast?city=${q}`)
      ])
      setWeather(cur); setForecast(fc?.items || [])
      // 즐겨찾기 선택 시에도 지도는 도시 지오코딩을 사용하도록 좌표를 비웁니다.
      setCoords(null)
      setMapCenter(null)
      setSelectedPlace(null)
      // 장소 검색 상태 초기화
      setShowPlaceResults(false)
      setPlaceResults([])
      setPlacesSearchKey(null)
      setPlacesDebouncedKey(null)
      setSelectedDateTime(null)
    } catch (e) { setError('도시의 날씨 정보를 불러오지 못했습니다.') }
    finally { setLoading(false) }
  }

  return (
    <div className="container">
      {notice && (
        <div className="notice" style={{ marginBottom: 12 }}>{notice}</div>
      )}
      <header className="header">
        <div className="title">
          <WeatherIcon sky={weather?.sky || '맑음'} size={28} />
          <div>
            <div style={{ fontSize: 20 }}>오늘의 날씨</div>
            <div className="subtitle">{locationLabel ? `${locationLabel} · ` : ''}현재 조건과 3시간 간격 예보</div>
          </div>
        </div>
        <div className="chips" style={{ alignItems: 'center' }}>
          <button className="btn btn-refresh" onClick={onRefresh}>새로고침</button>
          <button className="btn btn-fav" onClick={addFavorite}>즐겨찾기 추가</button>
          <button className="btn btn-search" onClick={onChat}>날씨챗</button>
          {isAuthenticated() ? (
            <>
              <span className="chip" style={{ cursor: 'default' }}><b>{userName || '사용자'}</b>님 반갑습니다</span>
              <button className="btn btn-logout" onClick={onLogout}>로그아웃</button>
            </>
          ) : (
            <button className="btn btn-search" onClick={onLogin}>로그인</button>
          )}
        </div>
      </header>

      <section className="section">
        <form onSubmit={onSearch} className="search">
          <input
            value={city}
            onChange={(e) => setCity(e.target.value)}
            onFocus={() => setSelectedDateTime(null)}
            placeholder="도시명을 입력하세요"
          />
          <button type="submit" className="btn btn-search">검색</button>
        </form>
        {favorites.length > 0 && (
          <div className="chips" style={{ marginTop: 10, flexWrap: 'wrap' }}>
            {favorites.map((f) => (
              <span key={f} className="chip" onClick={() => selectFavorite(f)}>{f}</span>
            ))}
          </div>
        )}
      </section>

      <section className="section" style={{ marginTop: 12 }}>
        <h2>지도</h2>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            setSelectedPlace(null)
            setShowPlaceResults(true)
            setPlacesSearchKey(placeQuery)
          }}
          className="search"
          style={{ marginBottom: 8 }}
        >
          <input
            value={placeQuery}
            onChange={(e) => setPlaceQuery(e.target.value)}
            placeholder="장소 검색(예: 서울역, 카페, 공원)"
          />
          <button type="submit" className="btn btn-search">장소 검색</button>
        </form>
        <div className="chips" style={{ marginBottom: 8, gap: 8 }}>
          <button
            className="btn btn-refresh"
            onClick={() => {
              if (!navigator.geolocation) return
              navigator.geolocation.getCurrentPosition(
                (pos) => {
                  const c = { lat: pos.coords.latitude, lon: pos.coords.longitude }
                  setCoords(c)
                  setLocationLabel('현재 위치')
                  setCity('')
                  setSelectedPlace(null)
                },
                () => {}
              )
            }}
          >현재 위치로</button>
          <button
            className="btn btn-search"
            onClick={() => {
              if (!mapCenter) return
              setCoords({ lat: mapCenter.lat, lon: mapCenter.lon })
              setLocationLabel('지도 중심')
              setCity('')
              setSelectedPlace(null)
            }}
          >지도 중심으로 조회</button>
        </div>
        <KakaoMap
          coords={coords}
          city={(locationLabel && locationLabel !== '현재 위치') ? locationLabel : (city.trim() || null)}
          level={7}
          height={360}
          placesKeyword={showPlaceResults ? placesDebouncedKey : null}
          onPlacesResult={(list) => setPlaceResults(list)}
          onClick={(pos) => {
            setCoords({ lat: pos.lat, lon: pos.lon })
            setLocationLabel('지도 선택 위치')
            setCity('')
            setSelectedPlace(null)
            // 장소 검색 상태 초기화
            setShowPlaceResults(false)
            setPlaceResults([])
            setPlacesSearchKey(null)
            setPlacesDebouncedKey(null)
          }}
          onCenterChange={(c) => {
            setMapCenter({ lat: c.lat, lon: c.lon })
          }}
          markerCities={favorites}
          onMarkerClick={({ city: cname, lat, lon }) => {
            setCoords({ lat, lon })
            setLocationLabel(cname)
            setCity('')
            setSelectedPlace(null)
            // 장소 검색 상태 초기화
            setShowPlaceResults(false)
            setPlaceResults([])
            setPlacesSearchKey(null)
            setPlacesDebouncedKey(null)
          }}
          selectedPlace={selectedPlace}
        />
        {showPlaceResults && placeResults.length > 0 && (
          <div className="card" style={{ marginTop: 8 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
              <div style={{ fontWeight: 600 }}>검색 결과</div>
              <button
                className="btn btn-fav"
                onClick={() => {
                  setShowPlaceResults(false)
                  setPlaceResults([])
                  setPlacesSearchKey(null)
                  setPlacesDebouncedKey(null)
                }}
              >닫기</button>
            </div>
            <ul style={{ listStyle: 'none', padding: 0, margin: 0, display: 'grid', gap: 6 }}>
              {placeResults.map((p, i) => (
                <li key={`${p.name}-${i}`} className="chip" style={{ display: 'flex', justifyContent: 'space-between' }}
                  onClick={() => {
                    setCoords({ lat: p.lat, lon: p.lon })
                    setLocationLabel(p.name)
                    setCity('')
                    setSelectedPlace({ name: p.name, lat: p.lat, lon: p.lon })
                    // 선택 직후 리스트 닫기
                    setShowPlaceResults(false)
                    setPlacesSearchKey(null)
                    setPlacesDebouncedKey(null)
                  }}
                >
                  <span>{p.name}</span>
                  <span className="muted" style={{ marginLeft: 8 }}>{p.address}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
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
            <TempChart items={forecast} onSelect={(dt) => setSelectedDateTime(dt)} />
          </div>
        )}
        {forecast && forecast.length > 0 && (
          <div style={{ marginTop: 16 }}>
            <h2>예보</h2>
            <ForecastList items={forecast} selectedDateTime={selectedDateTime || undefined} />
          </div>
        )}
      </section>
    </div>
  )
}
