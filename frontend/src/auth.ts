export function isAuthenticated(): boolean {
  return !!localStorage.getItem('accessToken')
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
