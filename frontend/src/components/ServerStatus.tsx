import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { BASE } from '../api/client'

export type ServerState = 'checking' | 'ready' | 'waking' | 'degraded'

type Ctx = { state: ServerState; waitedSec: number; recovered: boolean; recheck: () => void }

const ServerStatusContext = createContext<Ctx>({ state: 'ready', waitedSec: 0, recovered: false, recheck: () => {} })

export const useServerStatus = () => useContext(ServerStatusContext)

const PROBE_TIMEOUT_MS = 6000
const RETRY_MS = 3000
const SHOW_CHECKING_AFTER_SEC = 2
const LONG_WAIT_SEC = 180
const RECOVERED_SHOWN_MS = 6000

/** 서버가 응답하는지 한 번 확인한다. 응답이 아예 없거나 호스팅의 502/503/504면 깨우는 중, DB가 안 되면 degraded. */
async function probe(): Promise<'ready' | 'waking' | 'degraded'> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS)
  try {
    const res = await fetch(`${BASE}/api/health?t=${Date.now()}`, { signal: controller.signal })
    if (res.ok) return 'ready'
    if ([502, 503, 504].includes(res.status)) {
      const body = await res.json().catch(() => null)
      return body?.reason === 'database' ? 'degraded' : 'waking'
    }
    // 404 등: 서버는 응답하고 있다 (이 엔드포인트가 없는 이전 버전이 배포된 동안 포함)
    return 'ready'
  } catch {
    return 'waking'
  } finally {
    clearTimeout(timer)
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export function ServerStatusProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<ServerState>('checking')
  const [waitedSec, setWaitedSec] = useState(0)
  const [recovered, setRecovered] = useState(false)
  const [epoch, setEpoch] = useState(0) // 값이 바뀌면 확인을 처음부터 다시 시작한다
  const startedAt = useRef(Date.now())
  const everNotReady = useRef(false)
  const stateRef = useRef<ServerState>('checking')

  const recheck = useCallback(() => {
    // 이미 확인 중이거나 서버를 기다리는 중이면 그대로 둔다 (경과 시간이 처음으로 돌아가지 않게)
    if (stateRef.current !== 'ready') return
    startedAt.current = Date.now()
    setWaitedSec(0)
    stateRef.current = 'checking'
    setState('checking')
    setEpoch((n) => n + 1)
  }, [])

  useEffect(() => {
    let cancelled = false
    startedAt.current = Date.now()
    const tick = setInterval(() => setWaitedSec(Math.floor((Date.now() - startedAt.current) / 1000)), 1000)
    ;(async () => {
      while (!cancelled) {
        const s = await probe()
        if (cancelled) return
        stateRef.current = s
        setState(s)
        if (s === 'ready') {
          if (everNotReady.current) {
            setRecovered(true)
            setTimeout(() => setRecovered(false), RECOVERED_SHOWN_MS)
          }
          clearInterval(tick)
          return
        }
        everNotReady.current = true
        await sleep(RETRY_MS)
      }
    })()
    return () => {
      cancelled = true
      clearInterval(tick)
    }
  }, [epoch])

  const value = useMemo(() => ({ state, waitedSec, recovered, recheck }), [state, waitedSec, recovered, recheck])
  return <ServerStatusContext.Provider value={value}>{children}</ServerStatusContext.Provider>
}

/** 화면 위쪽에 서버 상태를 알려 주는 안내. 정상일 때는 보이지 않는다. */
export function ServerBanner() {
  const { state, waitedSec, recovered, recheck } = useServerStatus()
  if (state === 'ready' && !recovered) return null
  if (state === 'checking' && waitedSec < SHOW_CHECKING_AFTER_SEC) return null

  let kind = 'warn'
  let title = ''
  let detail = ''
  if (state === 'ready') {
    kind = 'notice'
    title = '서버가 준비됐어요. 이제 로그인과 챗을 쓸 수 있어요.'
  } else if (state === 'checking') {
    title = '서버 상태를 확인하는 중이에요...'
  } else if (state === 'degraded') {
    title = '서버는 켜졌지만 데이터베이스에 아직 연결되지 않았어요.'
    detail = '잠시 후 자동으로 다시 확인해요. 그 사이 로그인과 챗은 실패할 수 있어요.'
  } else {
    title = `서버를 깨우는 중이에요 (${waitedSec}초 경과)`
    detail =
      '무료 호스팅이라 한동안 접속이 없으면 서버가 잠들고, 첫 접속 때는 다시 켜지는 데 시간이 걸려요. 그 사이 로그인과 챗은 실패할 수 있어요. 준비되면 이 안내가 자동으로 바뀝니다.'
  }

  return (
    <div className="server-banner-wrap">
      <div className={`${kind} server-banner`} role="status" aria-live="polite">
        <strong>{title}</strong>
        {detail && <div style={{ marginTop: 4, fontSize: 13 }}>{detail}</div>}
        {state !== 'ready' && waitedSec >= LONG_WAIT_SEC && (
          <div style={{ marginTop: 6, fontSize: 13 }}>
            오래 걸리고 있어요. 잠시 후에도 그대로면 페이지를 새로고침해 주세요.{' '}
            <button type="button" className="btn btn-refresh" onClick={recheck} style={{ padding: '4px 10px' }}>지금 다시 확인</button>
          </div>
        )}
      </div>
    </div>
  )
}
