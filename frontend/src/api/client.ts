import { ServerUnreachableError } from './errors'

export const BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export async function api(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('accessToken')
  const headers = new Headers(options.headers || {})
  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, { ...options, headers })
  } catch {
    throw new ServerUnreachableError()
  }
  const text = await res.text()
  let data: any = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (!res.ok) {
    // 서비스가 보내는 오류는 JSON(ProblemDetail)이다. 502/503/504인데 JSON이 아니면 호스팅이 돌려준 페이지(서버가 깨어나는 중)다.
    if ([502, 503, 504].includes(res.status) && typeof data === 'string') throw new ServerUnreachableError()
    throw new Error(typeof data === 'string' ? data : data?.detail || data?.message || 'Request failed')
  }
  return data
}
