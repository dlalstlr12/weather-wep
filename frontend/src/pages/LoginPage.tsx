import { useState, useEffect } from 'react'
import { api } from '../api/client'
import { useNavigate, Link } from 'react-router-dom'
import BrandBar from '../components/BrandBar'
import { setFlash, consumeFlash } from '../flash'

export default function LoginPage() {
  const nav = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    const msg = consumeFlash()
    if (msg) setNotice(msg)
  }, [])

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
        setFlash('로그인되었습니다.')
        nav('/', { replace: true })
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
      <BrandBar align="center" size={40} />
      <div className="chips" style={{ marginBottom: 8 }}>
        <button className="btn btn-refresh" onClick={() => nav('/')}>메인으로</button>
      </div>
      <h2>로그인</h2>
      {notice && <div className="notice" style={{ marginBottom: 12 }}>{notice}</div>}
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
        <button type="submit" className="btn btn-search" disabled={loading}>{loading ? '처리 중...' : '로그인'}</button>
      </form>
      <div style={{ marginTop: 12 }}>
        <span className="muted">계정이 없으신가요? </span>
        <Link to="/signup">회원가입</Link>
      </div>
    </div>
  )
}
