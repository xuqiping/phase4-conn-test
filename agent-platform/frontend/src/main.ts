import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import router from './router'
import App from './App.vue'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

// 导入全局样式（顺序很重要：原语 -> 变量 -> 主题 -> 纹理 -> 全局）
import './styles/tokens-ink.scss'
import './styles/variables.scss'
import './styles/themes/deep-space.scss'
import './styles/themes/dark-pro.scss'
import './styles/themes/cyber-glow.scss'
import './styles/themes/ye-mo.scss'
import './styles/themes/xuan-zhi.scss'
import './styles/texture.scss'
import './styles/global.scss'

const app = createApp(App)

// 注册插件
app.use(createPinia())
app.use(router)
app.use(naive)

app.mount('#app')
