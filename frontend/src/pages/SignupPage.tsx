import { useState } from 'react'
import { api } from '../api/client'
import { useNavigate, Link } from 'react-router-dom'
import { setFlash } from '../flash'

export default function SignupPage() {
  const nav = useNavigate()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!name.trim() || !email.trim() || !password) {
      setError('모든 필드를 입력해주세요.')
      return
    }
    if (password !== confirm) {
      setError('비밀번호가 일치하지 않습니다.')
      return
    }
    setLoading(true)
    try {
      await api('/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name.trim(), email: email.trim(), password })
      })
      // 회원가입 성공 후 로그인 페이지로 이동
      setFlash('회원가입이 완료되었습니다. 로그인해 주세요.')
      nav('/login', { replace: true })
    } catch (e) {
      setError('회원가입에 실패했습니다.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="container small">
      <h2>회원가입</h2>
      <form onSubmit={onSubmit} className="form">
        <label>
          이름
          <input value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label>
          이메일
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label>
          비밀번호
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        <label>
          비밀번호 확인
          <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </label>
        {error && <p className="error">{error}</p>}
        <button type="submit" className="btn btn-search" disabled={loading}>{loading ? '처리 중...' : '회원가입'}</button>
      </form>
      <div style={{ marginTop: 12 }}>
        <span className="muted">이미 계정이 있으신가요? </span>
        <Link to="/login">로그인</Link>
      </div>
    </div>
  )
}
