import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import ScreenshotOverlayHost from './components/ScreenshotOverlayHost.vue'
import { createPersistPlugin } from './plugins/persistPlugin'

// Import global styles
import './styles/global.css'

const isScreenshotOverlayWindow = new URLSearchParams(window.location.search).has('screenshotOverlay')
const app = createApp(isScreenshotOverlayWindow ? ScreenshotOverlayHost : App)
const pinia = createPinia()

app.use(pinia)
pinia.use(createPersistPlugin())
app.mount('#app')

// Expose pinia to window for testing/debugging (development only)
if (import.meta.env.DEV) {
  ;(window as any).__PINIA__ = pinia
}
