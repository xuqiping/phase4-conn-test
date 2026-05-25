import { createApp } from 'vue'
import { createPinia } from 'pinia'
import naive from 'naive-ui'
import router from './router'
import App from './App.vue'

// 导入全局样式（顺序很重要：变量 -> 主题 -> 全局）
import './styles/variables.scss'
import './styles/themes/deep-space.scss'
import './styles/themes/dark-pro.scss'
import './styles/themes/cyber-glow.scss'
import './styles/global.scss'

const app = createApp(App)

// 注册插件
app.use(createPinia())
app.use(router)
app.use(naive)

app.mount('#app')
