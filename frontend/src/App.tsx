import AppRoutes from './routes'
import { ServerBanner, ServerStatusProvider } from './components/ServerStatus'

function App() {
  return (
    <ServerStatusProvider>
      <ServerBanner />
      <AppRoutes />
    </ServerStatusProvider>
  )
}

export default App
