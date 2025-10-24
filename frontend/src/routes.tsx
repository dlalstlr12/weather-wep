import { Routes, Route, Navigate } from 'react-router-dom'
import MainPage from './pages/MainPage'
import LoginPage from './pages/LoginPage'
import SignupPage from './pages/SignupPage'
import ChatPage from './pages/ChatPage'
import { useAuth } from './auth'

export default function AppRoutes() {
  const authed = useAuth()
  return (
    <Routes>
      <Route path="/" element={<MainPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route
        path="/chat"
        element={authed ? <ChatPage /> : <Navigate to="/login" replace />}
      />
    </Routes>
  )
}
