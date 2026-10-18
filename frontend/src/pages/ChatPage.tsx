import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BrandBar from '../components/BrandBar'
import Markdown from '../components/Markdown'

type Msg = { id: string; role: 'user' | 'assistant'; content: string; at: number }
type Coords = { lat: number; lon: number }

export default function ChatPage() {
  const nav = useNavigate()
  const [msgs, setMsgs] = useState<Msg[]>([{
    id: 'hello', role: 'assistant', at: Date.now(),
    content: '안녕하세요! 날씨 정보를 도와드릴게요. "현재 날씨" 또는 "서울 내일 날씨"처럼 물어보세요.'
  }])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const wrapRef = useRef<HTMLDivElement | null>(null)
  const [coords, setCoords] = useState<Coords | null>(null)

  useEffect(() => {
    if (!navigator.geolocation) return
    navigator.geolocation.getCurrentPosition(
      (pos) => setCoords({ lat: pos.coords.latitude, lon: pos.coords.longitude }),
      () => {}
    )
  }, [])

  useEffect(() => {
    // auto scroll to bottom on new message
    if (wrapRef.current) {
      wrapRef.current.scrollTop = wrapRef.current.scrollHeight
    }
  }, [msgs])

  const [, setTyping] = useState(false)
  const [status, setStatus] = useState('')
  const [ctrl, setCtrl] = useState<AbortController | null>(null)

  const send = async () => {
    const text = input.trim()
    if (!text || loading) return
    const u: Msg = { id: crypto.randomUUID(), role: 'user', content: text, at: Date.now() }
    setMsgs((m) => [...m, u])
    setInput('')
    setLoading(true)
    setTyping(true)
    try {
      await requestAssistantStream([...msgs, u])
    } catch (e) {
      setMsgs((m) => [...m, { id: crypto.randomUUID(), role: 'assistant', at: Date.now(), content: '죄송해요, 응답 중 오류가 발생했어요.' }])
    } finally {
      setLoading(false)
      setTyping(false)
    }
  }

  // 서버가 보내는 SSE 이벤트: status(도구 조회 중), delta(답변 조각), reset(지금까지의 조각 삭제), done, error
  const requestAssistantStream = async (history: Msg[]): Promise<void> => {
    const assistantId = crypto.randomUUID()
    // 미리 assistant 메시지 placeholder 추가 (내용이 생기기 전에는 화면에 그리지 않는다)
    setMsgs((m) => [...m, { id: assistantId, role: 'assistant', content: '', at: Date.now() }])
    const setContent = (fn: (prev: string) => string) =>
      setMsgs((m) => m.map(msg => msg.id === assistantId ? { ...msg, content: fn(msg.content) } : msg))

    const messages = history.map(h => ({ role: h.role, content: h.content }))
    const controller = new AbortController()
    setCtrl(controller)
    try {
      const res = await fetch(`${(import.meta as any).env.VITE_API_BASE_URL || 'http://localhost:8080'}/api/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(localStorage.getItem('accessToken') ? { 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` } : {})
        },
        body: JSON.stringify({ messages, lat: coords?.lat ?? null, lon: coords?.lon ?? null }),
        signal: controller.signal
      })
      if (!res.ok || !res.body) throw new Error('스트리밍 시작 실패')

      const reader = res.body.getReader()
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let finished = false
      let gotText = false
      while (!finished) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // SSE 프레임은 빈 줄로 구분된다
        const frames = buffer.split(/\r?\n\r?\n/)
        buffer = frames.pop() || ''
        for (const frame of frames) {
          let event = 'message'
          const dataLines: string[] = []
          for (const line of frame.split(/\r?\n/)) {
            if (line.startsWith('event:')) event = line.slice(6).trim()
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
          }
          const data = dataLines.length ? JSON.parse(dataLines.join('\n')) : {}
          if (event === 'status') {
            setStatus(data.text || '')
          } else if (event === 'delta') {
            gotText = true
            setStatus('')
            setContent((prev) => prev + (data.text || ''))
          } else if (event === 'reset') {
            gotText = false
            setContent(() => '')
          } else if (event === 'error') {
            throw new Error(data.message || '응답 중 오류가 발생했어요.')
          } else if (event === 'done') {
            finished = true
          }
        }
      }
      if (!gotText) throw new Error('응답이 비어 있어요.')
    } catch (e: any) {
      if (e?.name === 'AbortError') {
        setContent((prev) => prev || '(응답을 취소했어요)')
        return
      }
      setContent(() => '죄송해요, 응답 중 오류가 발생했어요.')
    } finally {
      setStatus('')
      setCtrl(null)
    }
  }

  useEffect(() => {
    return () => {
      // 컴포넌트 언마운트 시 진행 중 스트림 중단
      try { ctrl?.abort() } catch {}
    }
  }, [])

  return (
    <div className="container">
      <BrandBar align="center" size={48} />
      <div className="chips" style={{ marginBottom: 8 }}>
        <button className="btn btn-refresh" onClick={() => nav('/')}>메인으로</button>
      </div>
      <h2>날씨 챗</h2>
      <div ref={wrapRef} className="card" style={{ height: 420, overflow: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {msgs.filter(m => m.role === 'user' || m.content).map(m => (
          <div key={m.id} style={{ display: 'flex', justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start' }}>
            <div style={{ maxWidth: '78%', padding: '10px 12px', borderRadius: 14, whiteSpace: 'pre-wrap', lineHeight: 1.4,
              background: m.role === 'user' ? '#6366f1' : 'rgba(255,255,255,0.75)', color: m.role === 'user' ? '#fff' : '#0f172a' }}>
              {m.role === 'assistant' ? (
                <Markdown>{m.content}</Markdown>
              ) : (
                m.content
              )}
            </div>
          </div>
        ))}
        {loading && <div className="muted">{status || '응답 생성 중...'}</div>}
      </div>
      <form onSubmit={(e) => { e.preventDefault(); send() }} className="search" style={{ marginTop: 12, display: 'flex', gap: 8 }}>
        <input style={{ flex: 1 }} value={input} onChange={(e) => setInput(e.target.value)} placeholder="메시지를 입력하세요" />
        {!loading ? (
          <button type="submit" className="btn btn-search" disabled={loading || !input.trim()}>보내기</button>
        ) : (
          <button type="button" className="btn btn-danger" onClick={() => { try { ctrl?.abort() } catch {} }}>
            취소
          </button>
        )}
      </form>
    </div>
  )
}
