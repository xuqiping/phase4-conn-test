import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { createPersistPlugin } from './plugins/persistPlugin'

// Import global styles
import './styles/global.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
pinia.use(createPersistPlugin())
app.mount('#app')

// Expose pinia to window for testing/debugging (development only)
if (import.meta.env.DEV) {
  ;(window as any).__PINIA__ = pinia
}
