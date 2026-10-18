import { useEffect, useRef, useState } from 'react'
import { ensureKakao } from './kakaoSdk'

export type Place = { name: string; address: string; lat: number; lon: number }

type Props = { onSelect: (place: Place) => void }

const DEBOUNCE_MS = 300
const MIN_CHARS = 2

/** 장소 검색창. 입력하면 검색창 바로 아래에 결과 목록이 나오고, 항목을 고르면 onSelect로 넘긴다. */
export default function PlaceSearch({ onSelect }: Props) {
  const appKey = (import.meta as any).env?.VITE_KAKAO_MAPS_KEY as string | undefined
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Place[]>([])
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const requestId = useRef(0)
  const justSelected = useRef(false)

  const search = async (keyword: string) => {
    const q = keyword.trim()
    if (q.length < MIN_CHARS) {
      setResults([]); setStatus('idle'); setOpen(false)
      return
    }
    if (!appKey) { setStatus('error'); setOpen(true); return }
    const id = ++requestId.current
    setStatus('loading'); setOpen(true)
    try {
      const kakao = await ensureKakao(appKey)
      new kakao.maps.services.Places().keywordSearch(q, (data: any[], st: string) => {
        if (id !== requestId.current) return // 더 새 검색이 있으면 이 응답은 버린다
        if (st === kakao.maps.services.Status.OK && data) {
          setResults(data.slice(0, 8).map((p) => ({
            name: p.place_name,
            address: p.road_address_name || p.address_name || '',
            lat: Number(p.y),
            lon: Number(p.x),
          })))
        } else {
          setResults([])
        }
        setActive(-1)
        setStatus('done')
      })
    } catch {
      if (id === requestId.current) { setResults([]); setStatus('error') }
    }
  }

  // 입력이 멈추고 잠시 뒤에 검색한다. 목록에서 항목을 고른 직후에는 검색하지 않는다.
  useEffect(() => {
    if (justSelected.current) { justSelected.current = false; return }
    const t = setTimeout(() => search(query), DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [query])

  // 바깥을 누르면 목록을 닫는다
  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [])

  const choose = (p: Place) => {
    justSelected.current = true
    requestId.current++ // 진행 중인 검색 응답은 버린다
    setQuery(p.name)
    setOpen(false)
    setActive(-1)
    onSelect(p)
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' && results.length > 0) {
      e.preventDefault(); setOpen(true); setActive((a) => (a + 1) % results.length)
    } else if (e.key === 'ArrowUp' && results.length > 0) {
      e.preventDefault(); setOpen(true); setActive((a) => (a <= 0 ? results.length - 1 : a - 1))
    } else if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  const onSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (open && active >= 0 && results[active]) choose(results[active])
    else search(query)
  }

  return (
    <div ref={wrapRef} className="place-search">
      <form onSubmit={onSubmit} className="search" role="search">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => { if (results.length > 0 || status === 'done') setOpen(true) }}
          onKeyDown={onKeyDown}
          placeholder="장소 검색(예: 서울역, 카페, 공원)"
          autoComplete="off"
          role="combobox"
          aria-expanded={open}
          aria-controls="place-search-list"
          aria-autocomplete="list"
        />
        <button type="submit" className="btn btn-search">장소 검색</button>
      </form>
      {open && (
        <ul id="place-search-list" className="place-list" role="listbox">
          {status === 'loading' && <li className="place-empty">검색 중...</li>}
          {status === 'error' && <li className="place-empty">장소 검색을 사용할 수 없습니다. 지도 키 설정을 확인하세요.</li>}
          {status === 'done' && results.length === 0 && <li className="place-empty">검색 결과가 없습니다.</li>}
          {status !== 'error' && results.map((p, i) => (
            <li
              key={`${p.name}-${p.lat}-${p.lon}`}
              role="option"
              aria-selected={i === active}
              className={`place-item${i === active ? ' active' : ''}`}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => { e.preventDefault(); choose(p) }}
            >
              <span className="place-name">{p.name}</span>
              <span className="place-addr">{p.address}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
