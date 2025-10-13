import { Routes, Route, Navigate } from 'react-router-dom'
import MainPage from './pages/MainPage'
import LoginPage from './pages/LoginPage'
import ChatPage from './pages/ChatPage'

function isAuthenticated() {
  return !!localStorage.getItem('accessToken')
}

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<MainPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/chat"
        element={isAuthenticated() ? <ChatPage /> : <Navigate to="/login" replace />}
      />
    </Routes>
  )
}
