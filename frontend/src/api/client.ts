const BASE = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export async function api(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('accessToken')
  const headers = new Headers(options.headers || {})
  if (!headers.has('Content-Type') && !(options.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)

  const res = await fetch(`${BASE}${path}`, { ...options, headers })
  const text = await res.text()
  let data: any = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (!res.ok) {
    throw new Error(typeof data === 'string' ? data : data?.message || 'Request failed')
  }
  return data
}
