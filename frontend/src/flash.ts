const KEY = 'flashMsg'

export function setFlash(message: string) {
  try { sessionStorage.setItem(KEY, message) } catch {}
}

export function consumeFlash(): string | null {
  try {
    const m = sessionStorage.getItem(KEY)
    if (m) sessionStorage.removeItem(KEY)
    return m
  } catch { return null }
}
