import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'

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

  const [typing, setTyping] = useState(false)
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

  const requestAssistantStream = async (history: Msg[]): Promise<void> => {
    const assistantId = crypto.randomUUID()
    // 미리 assistant 메시지 placeholder 추가
    setMsgs((m) => [...m, { id: assistantId, role: 'assistant', content: '', at: Date.now() }])

    const messages = history.map(h => ({ role: h.role, content: h.content }))
    const controller = new AbortController()
    setCtrl(controller)
    const res = await fetch(`${(import.meta as any).env.VITE_API_BASE_URL || 'http://localhost:8080'}/api/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(localStorage.getItem('accessToken') ? { 'Authorization': `Bearer ${localStorage.getItem('accessToken')}` } : {})
      },
      body: JSON.stringify({ messages }),
      signal: controller.signal
    })
    if (!res.ok || !res.body) throw new Error('스트리밍 시작 실패')

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 프레임 분리 (\n\n 기준)
      const parts = buffer.split(/\n\n/)
      buffer = parts.pop() || ''
      for (const chunk of parts) {
        // data: 내용 라인만 추출
        const lines = chunk.split(/\n/)
        for (const line of lines) {
          if (!line.startsWith('data:')) continue
          const data = line.slice(5).trim()
          if (data === '[DONE]') {
            return
          }
          if (data.startsWith('[ERROR]')) {
            throw new Error(data)
          }
          // 누적 반영
          setMsgs((m) => m.map(msg => msg.id === assistantId ? { ...msg, content: msg.content + data } : msg))
        }
      }
    }
    setCtrl(null)
  }

  useEffect(() => {
    return () => {
      // 컴포넌트 언마운트 시 진행 중 스트림 중단
      try { ctrl?.abort() } catch {}
    }
  }, [])

  return (
    <div className="container">
      <div className="chips" style={{ marginBottom: 8 }}>
        <button className="btn btn-refresh" onClick={() => nav('/')}>메인으로</button>
      </div>
      <h2>날씨 챗</h2>
      <div ref={wrapRef} className="card" style={{ height: 420, overflow: 'auto', padding: 16, display: 'flex', flexDirection: 'column', gap: 10 }}>
        {msgs.map(m => (
          <div key={m.id} style={{ display: 'flex', justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start' }}>
            <div style={{ maxWidth: '78%', padding: '10px 12px', borderRadius: 14, whiteSpace: 'pre-wrap', lineHeight: 1.4,
              background: m.role === 'user' ? '#6366f1' : 'rgba(255,255,255,0.75)', color: m.role === 'user' ? '#fff' : '#0f172a' }}>
              {m.content}
            </div>
          </div>
        ))}
        {loading && <div className="muted">응답 생성 중...</div>}
      </div>
      <form onSubmit={(e) => { e.preventDefault(); send() }} className="search" style={{ marginTop: 12 }}>
        <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="메시지를 입력하세요" />
        <button type="submit" className="btn btn-search" disabled={loading || !input.trim()}>보내기</button>
      </form>
    </div>
  )
}

function dateKey(d: Date) {
  const y = d.getFullYear()
  const m = (d.getMonth() + 1).toString().padStart(2, '0')
  const day = d.getDate().toString().padStart(2, '0')
  return `${y}${m}${day}`
}
function addDaysKey(d: Date, n: number) {
  const t = new Date(d)
  t.setDate(t.getDate() + n)
  return dateKey(t)
}
function fmtNum(n: any) {
  const v = Number(n)
  if (Number.isFinite(v)) return Math.round(v * 10) / 10
  return n
}
