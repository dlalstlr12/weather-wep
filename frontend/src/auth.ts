const AUTH_EVENT = 'auth-changed'

export function isAuthenticated(): boolean {
  return !!localStorage.getItem('accessToken')
}

export function setAccessToken(token: string) {
  localStorage.setItem('accessToken', token)
  window.dispatchEvent(new Event(AUTH_EVENT))
}

export function clearAccessToken() {
  localStorage.removeItem('accessToken')
  window.dispatchEvent(new Event(AUTH_EVENT))
}

export function parseJwt(token: string): any | null {
  try {
    const [, payload] = token.split('.')
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(decodeURIComponent(escape(json)))
  } catch {
    return null
  }
}

export function getUserName(): string | null {
  const token = localStorage.getItem('accessToken')
  if (!token) return null
  const p = parseJwt(token)
  if (!p) return null
  return p.name || p.username || p.email || p.sub || null
}

import { useEffect, useState } from 'react'

export function useAuth(): boolean {
  const [authed, setAuthed] = useState(isAuthenticated())
  useEffect(() => {
    const onChange = () => setAuthed(isAuthenticated())
    window.addEventListener('storage', onChange)
    window.addEventListener(AUTH_EVENT, onChange)
    return () => {
      window.removeEventListener('storage', onChange)
      window.removeEventListener(AUTH_EVENT, onChange)
    }
  }, [])
  return authed
}
