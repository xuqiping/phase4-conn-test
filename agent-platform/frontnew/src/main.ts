import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useThemeStore } from './stores/theme'
import './theme/tokens/base.css'
import './theme/tokens/neon-pulse.css'
import './theme/tokens/calm-slate.css'
import './theme/tokens/hybrid-glow.css'
import './theme/tokens/cineon.css'
import './styles/global.scss'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
// 主题 store 初始化（与 index.html 内联脚本保持一致：URL 参数 > localStorage > 默认）
useThemeStore(pinia).init()
app.use(router)
app.mount('#app')
