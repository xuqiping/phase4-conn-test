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
