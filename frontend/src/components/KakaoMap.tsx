import { useEffect, useMemo, useRef, useState } from 'react'

// Minimal kakao types
declare global {
  interface Window { kakao?: any }
}

export type KakaoMapProps = {
  width?: string | number
  height?: string | number
  coords?: { lat: number; lon: number } | null
  city?: string | null
  level?: number // zoom level (1~14, bigger is farther)
  onClick?: (pos: { lat: number; lon: number }) => void
  onCenterChange?: (center: { lat: number; lon: number }) => void
  markerCities?: string[]
  onMarkerClick?: (payload: { city: string; lat: number; lon: number }) => void
  placesKeyword?: string | null
  onPlacesResult?: (list: Array<{ name: string; address: string; lat: number; lon: number }>) => void
  selectedPlace?: { name: string; lat: number; lon: number } | null
}

function loadKakaoSdk(appKey: string): Promise<any> {
  if (typeof window === 'undefined') return Promise.reject('no-window')
  if ((window as any).kakao && window.kakao.maps) return Promise.resolve(window.kakao)
  return new Promise((resolve, reject) => {
    const exist = document.querySelector('script[data-kakao-sdk="true"]') as HTMLScriptElement | null
    if (exist) {
      exist.addEventListener('load', () => resolve(window.kakao))
      exist.addEventListener('error', reject)
      return
    }
    const s = document.createElement('script')
    s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${appKey}&libraries=services&autoload=false`
    s.async = true
    s.defer = true
    s.dataset.kakaoSdk = 'true'
    s.addEventListener('load', () => resolve(window.kakao))
    s.addEventListener('error', reject)
    document.head.appendChild(s)
  })
}

export default function KakaoMap({ width = '100%', height = 360, coords, city, level = 7, onClick, onCenterChange, markerCities, onMarkerClick, placesKeyword, onPlacesResult, selectedPlace }: KakaoMapProps) {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const [ready, setReady] = useState(false)
  const appKey = useMemo(() => (import.meta as any).env?.VITE_KAKAO_MAPS_KEY as string, [])
  const placeMarkersRef = useRef<any[]>([])

  useEffect(() => {
    if (!appKey) return
    let mounted = true
    loadKakaoSdk(appKey)
      .then((kakao) => {
        if (!mounted) return
        kakao.maps.load(() => setReady(true))
      })
      .catch(() => {/* no-op */})
    return () => { mounted = false }
  }, [appKey])

  useEffect(() => {
    if (!ready || !containerRef.current || !window.kakao) return
    const kakao = window.kakao
    // default center: Seoul City Hall
    const center = coords ? new kakao.maps.LatLng(coords.lat, coords.lon) : new kakao.maps.LatLng(37.5665, 126.9780)
    const map = new kakao.maps.Map(containerRef.current, { center, level })

    const addMarker = (pos: any, title?: string) => {
      const marker = new kakao.maps.Marker({ position: pos })
      marker.setMap(map)
      if (title) {
        const iw = new kakao.maps.InfoWindow({ content: `<div style="padding:6px 8px;">${title}</div>` })
        iw.open(map, marker)
      }
    }

    if (coords) {
      const pos = new kakao.maps.LatLng(coords.lat, coords.lon)
      map.setCenter(pos)
      addMarker(pos, city || '선택 위치')
    } else if (city) {
      const geocoder = new kakao.maps.services.Geocoder()
      geocoder.addressSearch(city, (result: any[], status: string) => {
        if (status === kakao.maps.services.Status.OK && result && result.length > 0) {
          const r = result[0]
          const pos = new kakao.maps.LatLng(Number(r.y), Number(r.x))
          map.setCenter(pos)
          map.setLevel(level)
          addMarker(pos, city)
        }
      })
    }

    // Favorite city markers
    if (markerCities && markerCities.length > 0) {
      const geocoder = new kakao.maps.services.Geocoder()
      markerCities.slice(0, 20).forEach((cname) => {
        if (!cname) return
        geocoder.addressSearch(cname, (result: any[], status: string) => {
          if (status === kakao.maps.services.Status.OK && result && result.length > 0) {
            const r = result[0]
            const pos = new kakao.maps.LatLng(Number(r.y), Number(r.x))
            const marker = new kakao.maps.Marker({ position: pos })
            marker.setMap(map)
            const iw = new kakao.maps.InfoWindow({ content: `<div style="padding:6px 8px;">${cname}</div>` })
            kakao.maps.event.addListener(marker, 'click', function () {
              map.setCenter(pos)
              iw.open(map, marker)
              if (onMarkerClick) onMarkerClick({ city: cname, lat: Number(r.y), lon: Number(r.x) })
            })
          }
        })
      })
    }

    // Places search: emit list only (no auto markers/centering)
    if (placesKeyword && placesKeyword.trim().length > 0) {
      const places = new kakao.maps.services.Places()
      places.keywordSearch(placesKeyword.trim(), (data: any[], status: string) => {
        if (status !== kakao.maps.services.Status.OK || !data) {
          if (onPlacesResult) onPlacesResult([])
          return
        }
        const list: Array<{ name: string; address: string; lat: number; lon: number }> = []
        data.slice(0, 10).forEach((p: any) => {
          const lat = Number(p.y), lon = Number(p.x)
          list.push({ name: p.place_name, address: p.road_address_name || p.address_name || '', lat, lon })
        })
        if (onPlacesResult) onPlacesResult(list)
      })
    } else if (onPlacesResult) {
      onPlacesResult([])
    }

    // When a place is selected by parent, add a marker and center
    if (selectedPlace) {
      const pos = new kakao.maps.LatLng(selectedPlace.lat, selectedPlace.lon)
      const marker = new kakao.maps.Marker({ position: pos })
      marker.setMap(map)
      const iw = new kakao.maps.InfoWindow({ content: `<div style=\"padding:6px 8px;\">${selectedPlace.name}</div>` })
      iw.open(map, marker)
      map.setCenter(pos)
      // limit dynamic place markers to 8
      placeMarkersRef.current.push(marker)
      while (placeMarkersRef.current.length > 8) {
        const old = placeMarkersRef.current.shift()
        try { old.setMap(null) } catch {}
      }
      if (onMarkerClick) onMarkerClick({ city: selectedPlace.name, lat: selectedPlace.lat, lon: selectedPlace.lon })
    }

    // click handler
    if (onClick) {
      kakao.maps.event.addListener(map, 'click', function (mouseEvent: any) {
        const latlng = mouseEvent.latLng
        if (latlng) {
          onClick({ lat: latlng.getLat(), lon: latlng.getLng() })
        }
      })
    }

    // center change handler (idle is fired after drag/zoom)
    if (onCenterChange) {
      kakao.maps.event.addListener(map, 'idle', function () {
        const c = map.getCenter()
        if (c) onCenterChange({ lat: c.getLat(), lon: c.getLng() })
      })
    }

    // cleanup: nothing critical to destroy
    return () => {}
  }, [ready, coords?.lat, coords?.lon, city, level, markerCities && markerCities.join('|'), placesKeyword, selectedPlace && `${selectedPlace.name}:${selectedPlace.lat}:${selectedPlace.lon}`])

  return (
    <div style={{ width, height, borderRadius: 8, overflow: 'hidden', border: '1px solid #1f2937' }}>
      {!appKey && (
        <div style={{ padding: 12 }}>Kakao 지도 키가 설정되지 않았습니다. .env에 VITE_KAKAO_MAPS_KEY를 추가하세요.</div>
      )}
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
    </div>
  )
}
