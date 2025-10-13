import { useState } from 'react'
import { api } from '../api/client'
import { useNavigate } from 'react-router-dom'

export default function LoginPage() {
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await api('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password })
      })
      if (res?.accessToken) {
        localStorage.setItem('accessToken', res.accessToken)
        nav('/chat', { replace: true })
      } else {
        setError('로그인 실패')
      }
    } catch (e) {
      setError('로그인 실패')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="container small">
      <h2>로그인</h2>
      <form onSubmit={onSubmit} className="form">
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" disabled={loading}>{loading ? '처리 중...' : '로그인'}</button>
      </form>
    </div>
  )
}
