# 前端项目搭建 + 登录页 + 主题系统 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**目标：** 搭建Vue 3前端项目，完成全局暗色主题系统（3套主题）、登录/注册流程、主布局框架，实现可运行的前端应用。

**架构：** SPA单页应用，Pinia状态管理，CSS变量驱动主题切换，Axios封装JWT认证。

**技术栈：** Vue 3.4+, TypeScript 5, Vite 5, Pinia 2, Naive UI 2, Vue Router 4, Axios, Sass, @vicons/ionicons5

**设计参考：** `docs/superpowers/design/design-system.md`（主题色值、组件规范、布局规范）
**API参考：** `docs/superpowers/design/api-first-design.md`（认证模块5个API端点）

---

## 文件结构

```
e:\workspace\agent-platform\frontend\
├── package.json
├── vite.config.ts
├── tsconfig.json
├── tsconfig.node.json
├── env.d.ts
├── index.html
├── public/
│   └── favicon.svg
├── src/
│   ├── main.ts                      # 入口：创建Vue实例、注册插件
│   ├── App.vue                      # 根组件：Naive UI Provider + RouterView
│   ├── router/
│   │   └── index.ts                 # 路由配置 + 导航守卫
│   ├── stores/
│   │   ├── auth.ts                  # 认证状态（Pinia）
│   │   └── theme.ts                 # 主题状态（Pinia）
│   ├── api/
│   │   ├── request.ts               # Axios实例 + JWT拦截器
│   │   └── auth.ts                  # 认证API函数
│   ├── styles/
│   │   ├── variables.scss            # CSS变量骨架 + 默认值
│   │   ├── global.scss               # 全局重置 + 滚动条 + 通用类
│   │   └── themes/
│   │       ├── deep-space.scss       # Deep Space主题
│   │       ├── dark-pro.scss         # Dark Pro主题
│   │       └── cyber-glow.scss       # Cyber Glow主题
│   ├── layouts/
│   │   ├── MainLayout.vue            # 主布局（侧栏+顶栏+内容区）
│   │   └── AuthLayout.vue            # 登录布局（全屏居中卡片）
│   ├── views/
│   │   └── LoginView.vue             # 登录页（含注册弹窗）
│   ├── components/
│   │   ├── ThemeSwitcher.vue         # 主题切换组件
│   │   ├── Sidebar.vue               # 左侧导航栏
│   │   └── AppHeader.vue             # 顶部栏
│   ├── composables/
│   │   └── useTheme.ts               # 主题切换逻辑composable
│   └── utils/
│       └── storage.ts                # localStorage封装
```

---

### Task 1: 初始化Vue3项目
**Files:**
- Create: `agent-platform/frontend/package.json`
- Create: `agent-platform/frontend/vite.config.ts`
- Create: `agent-platform/frontend/tsconfig.json`
- Create: `agent-platform/frontend/tsconfig.node.json`
- Create: `agent-platform/frontend/env.d.ts`
- Create: `agent-platform/frontend/index.html`
- Create: `agent-platform/frontend/public/favicon.svg`
- Create: `agent-platform/frontend/src/main.ts`
- Create: `agent-platform/frontend/src/App.vue`

- [ ] **Step 1: 创建 package.json**

```json
{
  "name": "agent-platform-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "^3.4.27",
    "vue-router": "^4.3.2",
    "pinia": "^2.1.7",
    "naive-ui": "^2.38.1",
    "axios": "^1.7.2",
    "@vicons/ionicons5": "^0.12.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.4",
    "typescript": "^5.4.5",
    "vite": "^5.2.12",
    "vue-tsc": "^2.0.19",
    "sass": "^1.77.2"
  }
}
```

- [ ] **Step 2: 创建 vite.config.ts**

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    // 代理后端API请求到Spring Boot 8080端口
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        // 全局注入变量文件，组件内可直接使用变量
        additionalData: `@use "@/styles/variables" as *;\n`
      }
    }
  }
})
```

- [ ] **Step 3: 创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,

    /* Bundler mode */
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "preserve",

    /* Linting */
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,

    /* Path alias */
    "baseUrl": ".",
    "paths": {
      "@/*": ["src/*"]
    }
  },
  "include": ["src/**/*.ts", "src/**/*.d.ts", "src/**/*.tsx", "src/**/*.vue"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

- [ ] **Step 4: 创建 tsconfig.node.json**

```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true
  },
  "include": ["vite.config.ts"]
}
```

- [ ] **Step 5: 创建 env.d.ts**

```typescript
/// <reference types="vite/client" />

// 声明 .vue 模块，让TypeScript识别Vue单文件组件
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
```

- [ ] **Step 6: 创建 index.html**

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>多Agent智能体平台</title>
  </head>
  <body style="margin: 0; padding: 0; background: #060A13; color: #E8ECF4;">
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 7: 创建 public/favicon.svg**

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
  <defs>
    <linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" style="stop-color:#4F7CFF"/>
      <stop offset="100%" style="stop-color:#9333EA"/>
    </linearGradient>
  </defs>
  <rect width="32" height="32" rx="6" fill="url(#g)"/>
  <text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-weight="bold" font-family="sans-serif">A</text>
</svg>
```

- [ ] **Step 8: 创建 src/main.ts**

```typescript
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
```

- [ ] **Step 9: 创建 src/App.vue**

```vue
<template>
  <!-- Naive UI 的 ConfigProvider 用于全局配置暗色主题 -->
  <n-config-provider :theme="darkTheme" :locale="zhCN" :date-locale="dateZhCN">
    <n-message-provider>
      <n-dialog-provider>
        <n-notification-provider>
          <router-view />
        </n-notification-provider>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<script setup lang="ts">
import { darkTheme, zhCN, dateZhCN } from 'naive-ui'
</script>

<style lang="scss">
// 根组件无额外样式，所有样式通过全局文件控制
</style>
```

**验证：** 运行 `npm install && npm run dev`，浏览器访问 `http://localhost:5173` 应显示空白暗色页面（无报错）。

---

### Task 2: 全局样式 + 主题系统
**Files:**
- Create: `agent-platform/frontend/src/styles/variables.scss`
- Create: `agent-platform/frontend/src/styles/themes/deep-space.scss`
- Create: `agent-platform/frontend/src/styles/themes/dark-pro.scss`
- Create: `agent-platform/frontend/src/styles/themes/cyber-glow.scss`
- Create: `agent-platform/frontend/src/styles/global.scss`

- [ ] **Step 1: 创建 variables.scss — CSS变量骨架**

```scss
// ============================================================
// 设计Token — CSS自定义属性骨架
// 色值由各主题文件覆盖，此处定义默认值（等同于Deep Space主题）
// 参考: docs/superpowers/design/design-system.md
// ============================================================

:root {
  // === 主色 ===
  --color-primary:         #4F7CFF;
  --color-primary-hover:   #6B91FF;
  --color-primary-active:  #3A66E8;
  --color-primary-light:   rgba(79, 124, 255, 0.12);
  --color-primary-rgb:     79, 124, 255;

  // === 语义色 ===
  --color-secondary:       #8B8FA3;
  --color-success:         #4ADE80;
  --color-warning:         #FBBF24;
  --color-danger:          #F87171;
  --color-info:            #60A5FA;

  // === 背景层级 ===
  --color-bg:              #060A13;
  --color-surface:         #0C1220;
  --color-card:            #131B2E;
  --color-elevated:        #1A2440;
  --color-overlay:         rgba(6, 10, 19, 0.8);

  // === 文字 ===
  --color-text-primary:    #E8ECF4;
  --color-text-secondary:  #8B8FA3;
  --color-text-tertiary:   #4A5068;
  --color-text-inverse:    #060A13;
  --color-text-link:       #4F7CFF;

  // === 边框 ===
  --color-border:          #1E2A45;
  --color-border-light:    #151D32;
  --color-border-focus:    #4F7CFF;

  // === 渐变与特效 ===
  --color-gradient-start:  #4F7CFF;
  --color-gradient-end:    #9333EA;

  // === 字体系统 ===
  --font-family-base:  'Inter', 'PingFang SC', 'Microsoft YaHei', -apple-system, sans-serif;
  --font-family-code:  'JetBrains Mono', 'Fira Code', 'Consolas', monospace;

  --font-size-xs:    12px;
  --font-size-sm:    13px;
  --font-size-base:  14px;
  --font-size-md:    16px;
  --font-size-lg:    18px;
  --font-size-xl:    20px;
  --font-size-2xl:   24px;
  --font-size-3xl:   30px;

  --line-height-tight:   1.25;
  --line-height-base:    1.5;
  --line-height-relaxed: 1.75;

  --font-weight-normal:    400;
  --font-weight-medium:    500;
  --font-weight-semibold:  600;
  --font-weight-bold:      700;

  // === 间距系统 ===
  --spacing-0:   0;
  --spacing-1:   4px;
  --spacing-2:   8px;
  --spacing-3:   12px;
  --spacing-4:   16px;
  --spacing-5:   20px;
  --spacing-6:   24px;
  --spacing-8:   32px;
  --spacing-10:  40px;
  --spacing-12:  48px;
  --spacing-16:  64px;

  // === 圆角系统 ===
  --radius-none:  0;
  --radius-sm:    4px;
  --radius-base:  6px;
  --radius-md:    8px;
  --radius-lg:    12px;
  --radius-xl:    16px;
  --radius-full:  9999px;

  // === 阴影系统 ===
  --shadow-sm:      0 1px 2px rgba(0, 0, 0, 0.3);
  --shadow-base:    0 2px 8px rgba(0, 0, 0, 0.4);
  --shadow-md:      0 4px 16px rgba(0, 0, 0, 0.5);
  --shadow-lg:      0 8px 24px rgba(0, 0, 0, 0.6);
  --shadow-xl:      0 12px 40px rgba(0, 0, 0, 0.7);
  --shadow-primary: 0 4px 16px rgba(79, 124, 255, 0.25);
  --shadow-glow:    0 0 30px rgba(79, 124, 255, 0.1);

  // === 动效系统 ===
  --duration-instant: 100ms;
  --duration-fast:    200ms;
  --duration-normal:  300ms;
  --duration-slow:    500ms;
  --duration-slower:  800ms;

  --ease-linear:   linear;
  --ease-in:       cubic-bezier(0.4, 0, 1, 1);
  --ease-out:      cubic-bezier(0, 0, 0.2, 1);
  --ease-in-out:   cubic-bezier(0.4, 0, 0.2, 1);
  --ease-bounce:   cubic-bezier(0.68, -0.55, 0.265, 1.55);

  // === 布局常量 ===
  --sidebar-width:        240px;
  --sidebar-collapsed-width: 64px;
  --header-height:        56px;
}
```

- [ ] **Step 2: 创建 themes/deep-space.scss — Deep Space主题（深空）**

```scss
// ============================================================
// Deep Space 主题 — 深邃宇宙空间感
// 深蓝底 + 冷蓝强调 + 玻璃拟态
// ============================================================

[data-theme="deep-space"] {
  // === 主色 ===
  --color-primary:         #4F7CFF;
  --color-primary-hover:   #6B91FF;
  --color-primary-active:  #3A66E8;
  --color-primary-light:   rgba(79, 124, 255, 0.12);
  --color-primary-rgb:     79, 124, 255;

  // === 语义色 ===
  --color-secondary:       #8B8FA3;
  --color-success:         #4ADE80;
  --color-warning:         #FBBF24;
  --color-danger:          #F87171;
  --color-info:            #60A5FA;

  // === 背景层级 ===
  --color-bg:              #060A13;
  --color-surface:         #0C1220;
  --color-card:            #131B2E;
  --color-elevated:        #1A2440;
  --color-overlay:         rgba(6, 10, 19, 0.8);

  // === 文字 ===
  --color-text-primary:    #E8ECF4;
  --color-text-secondary:  #8B8FA3;
  --color-text-tertiary:   #4A5068;
  --color-text-inverse:    #060A13;
  --color-text-link:       #4F7CFF;

  // === 边框 ===
  --color-border:          #1E2A45;
  --color-border-light:    #151D32;
  --color-border-focus:    #4F7CFF;

  // === 特殊 ===
  --color-gradient-start:  #4F7CFF;
  --color-gradient-end:    #9333EA;
  --shadow-primary:        0 4px 16px rgba(79, 124, 255, 0.25);
  --shadow-glow:           0 0 30px rgba(79, 124, 255, 0.1);
}
```

- [ ] **Step 3: 创建 themes/dark-pro.scss — Dark Pro主题（暗夜专业）**

```scss
// ============================================================
// Dark Pro 主题 — 专业深灰色调
// 中性灰底 + 绿色强调 + 视觉疲劳最小
// ============================================================

[data-theme="dark-pro"] {
  // === 主色 ===
  --color-primary:         #10B981;
  --color-primary-hover:   #34D399;
  --color-primary-active:  #059669;
  --color-primary-light:   rgba(16, 185, 129, 0.12);
  --color-primary-rgb:     16, 185, 129;

  // === 语义色 ===
  --color-secondary:       #9CA3AF;
  --color-success:         #34D399;
  --color-warning:         #FBBF24;
  --color-danger:          #F87171;
  --color-info:            #60A5FA;

  // === 背景层级 ===
  --color-bg:              #0A0A0A;
  --color-surface:         #111111;
  --color-card:            #1A1A1A;
  --color-elevated:        #222222;
  --color-overlay:         rgba(0, 0, 0, 0.75);

  // === 文字 ===
  --color-text-primary:    #F3F4F6;
  --color-text-secondary:  #9CA3AF;
  --color-text-tertiary:   #4B5563;
  --color-text-inverse:    #0A0A0A;
  --color-text-link:       #10B981;

  // === 边框 ===
  --color-border:          #2A2A2A;
  --color-border-light:    #1F1F1F;
  --color-border-focus:    #10B981;

  // === 特殊 ===
  --color-gradient-start:  #10B981;
  --color-gradient-end:    #06B6D4;
  --shadow-primary:        0 4px 16px rgba(16, 185, 129, 0.2);
  --shadow-glow:           0 0 30px rgba(16, 185, 129, 0.08);
}
```

- [ ] **Step 4: 创建 themes/cyber-glow.scss — Cyber Glow主题（赛博辉光）**

```scss
// ============================================================
// Cyber Glow 主题 — 赛博朋克风格
// 暗紫底 + 霓虹粉蓝强调 + 发光边框
// ============================================================

[data-theme="cyber-glow"] {
  // === 主色 ===
  --color-primary:         #E040FB;
  --color-primary-hover:   #EA80FC;
  --color-primary-active:  #C026D3;
  --color-primary-light:   rgba(224, 64, 251, 0.12);
  --color-primary-rgb:     224, 64, 251;

  // === 语义色 ===
  --color-secondary:       #A78BFA;
  --color-success:         #4ADE80;
  --color-warning:         #FCD34D;
  --color-danger:          #FB7185;
  --color-info:            #38BDF8;

  // === 背景层级 ===
  --color-bg:              #0A0510;
  --color-surface:         #110B1A;
  --color-card:            #1A1028;
  --color-elevated:        #241638;
  --color-overlay:         rgba(10, 5, 16, 0.85);

  // === 文字 ===
  --color-text-primary:    #F0E6FF;
  --color-text-secondary:  #9B8BB8;
  --color-text-tertiary:   #5A4A72;
  --color-text-inverse:    #0A0510;
  --color-text-link:       #E040FB;

  // === 边框 ===
  --color-border:          #2D1F42;
  --color-border-light:    #1E1530;
  --color-border-focus:    #E040FB;

  // === 特殊 ===
  --color-gradient-start:  #E040FB;
  --color-gradient-end:    #38BDF8;
  --color-neon-pink:       #FF2E97;
  --color-neon-blue:       #00D4FF;
  --shadow-primary:        0 4px 20px rgba(224, 64, 251, 0.3);
  --shadow-glow:           0 0 40px rgba(224, 64, 251, 0.12);
}
```

- [ ] **Step 5: 创建 global.scss — 全局重置与通用样式**

```scss
// ============================================================
// 全局样式
// ============================================================

// === 重置 ===
*,
*::before,
*::after {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

html {
  font-size: 14px;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}

body {
  font-family: var(--font-family-base);
  font-size: var(--font-size-base);
  line-height: var(--line-height-base);
  color: var(--color-text-primary);
  background-color: var(--color-bg);
  min-height: 100vh;
  overflow-x: hidden;
}

a {
  color: var(--color-text-link);
  text-decoration: none;
  transition: color var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-primary-hover);
  }
}

// === 自定义滚动条 ===
::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: var(--color-border);
  border-radius: var(--radius-full);

  &:hover {
    background: var(--color-text-tertiary);
  }
}

// === 选中文本颜色 ===
::selection {
  background: var(--color-primary-light);
  color: var(--color-primary);
}

// === 通用工具类 ===
.text-primary { color: var(--color-text-primary); }
.text-secondary { color: var(--color-text-secondary); }
.text-tertiary { color: var(--color-text-tertiary); }
.text-accent { color: var(--color-primary); }
.text-success { color: var(--color-success); }
.text-warning { color: var(--color-warning); }
.text-danger { color: var(--color-danger); }

.flex-center {
  display: flex;
  align-items: center;
  justify-content: center;
}

.flex-between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

// === 背景渐变（用于登录页等场景）===
.gradient-bg {
  background: linear-gradient(
    135deg,
    var(--color-bg) 0%,
    var(--color-surface) 50%,
    var(--color-bg) 100%
  );
}

// === 玻璃拟态效果 ===
.glass {
  background: rgba(var(--color-primary-rgb), 0.05);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--color-border);
}

// === 发光边框 ===
.glow-border {
  border: 1px solid var(--color-border);
  box-shadow: var(--shadow-glow);
  transition: box-shadow var(--duration-normal) var(--ease-in-out);

  &:hover {
    box-shadow: var(--shadow-primary);
    border-color: var(--color-primary);
  }
}

// === 渐变按钮背景 ===
.gradient-primary {
  background: linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end));
}

// === 粒子动画关键帧（登录页背景用）===
@keyframes float {
  0%, 100% {
    transform: translateY(0) translateX(0);
  }
  25% {
    transform: translateY(-20px) translateX(10px);
  }
  50% {
    transform: translateY(-10px) translateX(-5px);
  }
  75% {
    transform: translateY(-30px) translateX(15px);
  }
}

@keyframes pulse-glow {
  0%, 100% {
    opacity: 0.3;
  }
  50% {
    opacity: 0.8;
  }
}

@keyframes fade-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
```

**验证：** 在浏览器开发者工具中检查 `<html>` 元素，手动添加 `data-theme="dark-pro"` 或 `data-theme="cyber-glow"` 属性，观察页面背景色和文字颜色变化。

---

### Task 3: Pinia Store + 路由 + Axios封装
**Files:**
- Create: `agent-platform/frontend/src/utils/storage.ts`
- Create: `agent-platform/frontend/src/stores/auth.ts`
- Create: `agent-platform/frontend/src/stores/theme.ts`
- Create: `agent-platform/frontend/src/router/index.ts`
- Create: `agent-platform/frontend/src/api/request.ts`
- Create: `agent-platform/frontend/src/api/auth.ts`

- [ ] **Step 1: 创建 utils/storage.ts — localStorage封装**

```typescript
// ============================================================
// localStorage 封装 — 类型安全的持久化存储
// ============================================================

/**
 * 存储键名常量
 */
export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'access_token',
  REFRESH_TOKEN: 'refresh_token',
  USER_INFO: 'user_info',
  THEME: 'app_theme',
  SIDEBAR_COLLAPSED: 'sidebar_collapsed'
} as const

/**
 * 安全地从 localStorage 读取数据
 * @param key 存储键名
 * @returns 解析后的数据，读取失败返回 null
 */
export function getStorage<T>(key: string): T | null {
  try {
    const value = localStorage.getItem(key)
    if (value === null) return null
    return JSON.parse(value) as T
  } catch {
    return null
  }
}

/**
 * 安全地将数据写入 localStorage
 * @param key 存储键名
 * @param value 要存储的数据
 */
export function setStorage<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch (error) {
    console.error('localStorage写入失败:', error)
  }
}

/**
 * 从 localStorage 移除指定键
 * @param key 存储键名
 */
export function removeStorage(key: string): void {
  localStorage.removeItem(key)
}

/**
 * 清除所有认证相关的存储数据
 */
export function clearAuthStorage(): void {
  removeStorage(STORAGE_KEYS.ACCESS_TOKEN)
  removeStorage(STORAGE_KEYS.REFRESH_TOKEN)
  removeStorage(STORAGE_KEYS.USER_INFO)
}
```

- [ ] **Step 2: 创建 stores/auth.ts — 认证状态Store**

```typescript
// ============================================================
// 认证状态管理 — Pinia Store
// 管理用户登录态、token、用户信息
// ============================================================

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api/auth'
import {
  STORAGE_KEYS,
  getStorage,
  setStorage,
  clearAuthStorage
} from '@/utils/storage'

/** 用户信息接口 */
export interface UserInfo {
  id: number
  username: string
  email: string | null
  avatar: string | null
  roles: string[]
  permissions: string[]
}

/** 登录请求参数 */
export interface LoginParams {
  username: string
  password: string
}

/** 注册请求参数 */
export interface RegisterParams {
  username: string
  email: string
  password: string
}

export const useAuthStore = defineStore('auth', () => {
  // === 状态 ===
  const accessToken = ref<string | null>(getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN))
  const refreshToken = ref<string | null>(getStorage<string>(STORAGE_KEYS.REFRESH_TOKEN))
  const userInfo = ref<UserInfo | null>(getStorage<UserInfo>(STORAGE_KEYS.USER_INFO))
  const loading = ref(false)

  // === 计算属性 ===
  /** 是否已登录 */
  const isLoggedIn = computed(() => !!accessToken.value && !!userInfo.value)

  /** 是否是管理员 */
  const isAdmin = computed(() => userInfo.value?.roles?.includes('admin') ?? false)

  // === Actions ===

  /**
   * 用户登录
   * 调用登录API，存储token和用户信息
   */
  async function login(params: LoginParams) {
    loading.value = true
    try {
      const res = await authApi.login(params)
      const { accessToken: at, refreshToken: rt, userInfo: info } = res.data

      accessToken.value = at
      refreshToken.value = rt
      userInfo.value = info

      // 持久化到 localStorage
      setStorage(STORAGE_KEYS.ACCESS_TOKEN, at)
      setStorage(STORAGE_KEYS.REFRESH_TOKEN, rt)
      setStorage(STORAGE_KEYS.USER_INFO, info)
    } finally {
      loading.value = false
    }
  }

  /**
   * 用户注册
   */
  async function register(params: RegisterParams) {
    loading.value = true
    try {
      await authApi.register(params)
    } finally {
      loading.value = false
    }
  }

  /**
   * 用户登出
   * 清除本地状态，可选调用后端登出接口
   */
  async function logout() {
    try {
      if (refreshToken.value) {
        await authApi.logout(refreshToken.value)
      }
    } catch {
      // 登出接口失败不影响本地清理
    } finally {
      // 清除状态
      accessToken.value = null
      refreshToken.value = null
      userInfo.value = null
      clearAuthStorage()
    }
  }

  /**
   * 获取当前用户信息（刷新页面时调用）
   */
  async function fetchUserInfo() {
    try {
      const res = await authApi.getMe()
      userInfo.value = res.data
      setStorage(STORAGE_KEYS.USER_INFO, res.data)
    } catch {
      // 获取失败，可能token已过期
      await logout()
    }
  }

  /**
   * 刷新访问令牌
   * @returns 新的访问令牌
   */
  async function refreshAccessToken(): Promise<string> {
    if (!refreshToken.value) {
      throw new Error('无刷新令牌')
    }

    const res = await authApi.refresh(refreshToken.value)
    const newToken = res.data.accessToken

    accessToken.value = newToken
    setStorage(STORAGE_KEYS.ACCESS_TOKEN, newToken)

    return newToken
  }

  /**
   * 检查用户是否拥有指定权限
   * @param permission 权限代码，如 'agent:create'
   */
  function hasPermission(permission: string): boolean {
    return userInfo.value?.permissions?.includes(permission) ?? false
  }

  return {
    // 状态
    accessToken,
    refreshToken,
    userInfo,
    loading,
    // 计算属性
    isLoggedIn,
    isAdmin,
    // Actions
    login,
    register,
    logout,
    fetchUserInfo,
    refreshAccessToken,
    hasPermission
  }
})
```

- [ ] **Step 3: 创建 stores/theme.ts — 主题状态Store**

```typescript
// ============================================================
// 主题状态管理 — Pinia Store
// 管理3套暗色主题的切换和持久化
// ============================================================

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { STORAGE_KEYS, getStorage, setStorage } from '@/utils/storage'

/** 主题名称类型 */
export type ThemeName = 'deep-space' | 'dark-pro' | 'cyber-glow'

/** 主题元信息（用于主题选择器展示）*/
export interface ThemeMeta {
  name: ThemeName
  label: string
  description: string
  /** 预览色块：主色 + 渐变起始色 + 渐变结束色 */
  colors: {
    primary: string
    gradientStart: string
    gradientEnd: string
    bg: string
  }
}

/** 所有可选主题列表 */
export const THEME_LIST: ThemeMeta[] = [
  {
    name: 'deep-space',
    label: 'Deep Space',
    description: '深邃宇宙 — 冷蓝强调，玻璃拟态',
    colors: {
      primary: '#4F7CFF',
      gradientStart: '#4F7CFF',
      gradientEnd: '#9333EA',
      bg: '#060A13'
    }
  },
  {
    name: 'dark-pro',
    label: 'Dark Pro',
    description: '暗夜专业 — 绿色强调，视觉舒适',
    colors: {
      primary: '#10B981',
      gradientStart: '#10B981',
      gradientEnd: '#06B6D4',
      bg: '#0A0A0A'
    }
  },
  {
    name: 'cyber-glow',
    label: 'Cyber Glow',
    description: '赛博辉光 — 霓虹多色，发光边框',
    colors: {
      primary: '#E040FB',
      gradientStart: '#E040FB',
      gradientEnd: '#38BDF8',
      bg: '#0A0510'
    }
  }
]

export const useThemeStore = defineStore('theme', () => {
  // === 状态 ===
  const currentTheme = ref<ThemeName>(
    getStorage<ThemeName>(STORAGE_KEYS.THEME) || 'deep-space'
  )

  // === 计算属性 ===
  /** 当前主题的元信息 */
  const currentThemeMeta = computed(() =>
    THEME_LIST.find(t => t.name === currentTheme.value) || THEME_LIST[0]
  )

  // === Actions ===

  /**
   * 设置主题
   * 更新CSS变量（通过data-theme属性）并持久化到localStorage
   */
  function setTheme(theme: ThemeName) {
    currentTheme.value = theme
    // 设置根元素的 data-theme 属性，触发CSS变量切换
    document.documentElement.setAttribute('data-theme', theme)
    // 持久化
    setStorage(STORAGE_KEYS.THEME, theme)
  }

  /**
   * 初始化主题（应用启动时调用）
   */
  function initTheme() {
    setTheme(currentTheme.value)
  }

  return {
    currentTheme,
    currentThemeMeta,
    setTheme,
    initTheme
  }
})
```

- [ ] **Step 4: 创建 router/index.ts — 路由配置**

```typescript
// ============================================================
// Vue Router 配置
// 路由定义 + 导航守卫（认证检查）
// ============================================================

import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { getStorage, STORAGE_KEYS } from '@/utils/storage'

// 路由定义
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: {
      layout: 'auth',
      title: '登录',
      requiresAuth: false
    }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    meta: { requiresAuth: true },
    children: [
      {
        path: '',
        redirect: '/agents'
      },
      {
        path: 'agents',
        name: 'AgentHall',
        component: () => import('@/views/AgentHallView.vue'),
        meta: { title: 'Agent大厅' }
      },
      {
        path: 'agents/:id',
        name: 'AgentDetail',
        component: () => import('@/views/AgentDetailView.vue'),
        meta: { title: 'Agent详情' }
      },
      {
        path: 'workflow',
        name: 'WorkflowList',
        component: () => import('@/views/WorkflowListView.vue'),
        meta: { title: '工作流列表' }
      },
      {
        path: 'workflow/:id',
        name: 'WorkflowEditor',
        component: () => import('@/views/WorkflowEditorView.vue'),
        meta: { title: '工作流编辑器' }
      }
    ]
  },
  // 兜底路由：未匹配的路径重定向到首页
  {
    path: '/:pathMatch(.*)*',
    redirect: '/agents'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫 — 认证检查
router.beforeEach((to, _from, next) => {
  // 设置页面标题
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} - 多Agent智能体平台` : '多Agent智能体平台'

  // 检查是否需要认证
  const requiresAuth = to.meta.requiresAuth !== false
  const hasToken = !!getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)

  if (requiresAuth && !hasToken) {
    // 需要认证但没有token，重定向到登录页
    next({
      path: '/login',
      query: { redirect: to.fullPath }
    })
  } else if (to.path === '/login' && hasToken) {
    // 已登录用户访问登录页，重定向到首页
    next({ path: '/agents' })
  } else {
    next()
  }
})

export default router
```

- [ ] **Step 5: 创建 api/request.ts — Axios实例与拦截器**

```typescript
// ============================================================
// Axios 请求封装
// - 自动添加JWT Authorization请求头
// - 401时自动刷新token
// - 统一错误处理（Naive UI message提示）
// ============================================================

import axios from 'axios'
import type { AxiosInstance, InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import { getStorage, setStorage, removeStorage, STORAGE_KEYS } from '@/utils/storage'

/** 后端统一响应格式 */
export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

/** 创建Axios实例 */
const request: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 是否正在刷新token的标记（防止并发刷新）
let isRefreshing = false
// 等待token刷新的请求队列
let pendingRequests: Array<(token: string) => void> = []

/**
 * 请求拦截器 — 自动添加Authorization头
 */
request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getStorage<string>(STORAGE_KEYS.ACCESS_TOKEN)
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

/**
 * 响应拦截器 — 统一错误处理 + 自动刷新token
 */
request.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data

    // 业务错误码处理
    if (res.code !== 200 && res.code !== 201 && res.code !== 202) {
      showErrorMessage(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }

    return response
  },
  async (error) => {
    const originalRequest = error.config

    // 401 Token过期 — 尝试刷新
    if (error.response?.status === 401 && !originalRequest._retry) {
      const errorCode = error.response?.data?.code

      // Token过期（业务码40101），尝试刷新
      if (errorCode === 40101) {
        if (isRefreshing) {
          // 正在刷新中，将请求加入等待队列
          return new Promise((resolve) => {
            pendingRequests.push((token: string) => {
              originalRequest.headers.Authorization = `Bearer ${token}`
              resolve(request(originalRequest))
            })
          })
        }

        originalRequest._retry = true
        isRefreshing = true

        try {
          const refreshToken = getStorage<string>(STORAGE_KEYS.REFRESH_TOKEN)
          if (!refreshToken) {
            throw new Error('无刷新令牌')
          }

          // 发起刷新请求
          const res = await axios.post<ApiResponse<{ accessToken: string }>>(
            '/api/auth/refresh',
            { refreshToken }
          )

          const newToken = res.data.data.accessToken
          setStorage(STORAGE_KEYS.ACCESS_TOKEN, newToken)

          // 执行等待队列中的请求
          pendingRequests.forEach(cb => cb(newToken))
          pendingRequests = []

          // 重试原始请求
          originalRequest.headers.Authorization = `Bearer ${newToken}`
          return request(originalRequest)
        } catch (refreshError) {
          // 刷新失败，清除认证信息，跳转登录页
          pendingRequests = []
          removeStorage(STORAGE_KEYS.ACCESS_TOKEN)
          removeStorage(STORAGE_KEYS.REFRESH_TOKEN)
          removeStorage(STORAGE_KEYS.USER_INFO)
          window.location.href = '/login'
          return Promise.reject(refreshError)
        } finally {
          isRefreshing = false
        }
      }
    }

    // 其他错误
    const message = error.response?.data?.message || error.message || '网络错误'
    showErrorMessage(message)
    return Promise.reject(error)
  }
)

/**
 * 显示错误消息（使用Naive UI的discrete message API）
 * 因为拦截器在组件外部运行，需要使用discrete方式创建message实例
 */
function showErrorMessage(message: string) {
  // 动态导入Naive UI的discrete API
  import('naive-ui').then(({ createDiscreteApi, darkTheme }) => {
    const { message } = createDiscreteApi(['message'], {
      configProviderProps: {
        theme: darkTheme
      }
    })
    message.error(message, { duration: 3000 })
  })
}

export default request
```

- [ ] **Step 6: 创建 api/auth.ts — 认证API函数**

```typescript
// ============================================================
// 认证模块API
// 对应后端 /api/auth/* 端点
// ============================================================

import request from './request'
import type { ApiResponse } from './request'
import type { UserInfo } from '@/stores/auth'

/** 登录响应数据 */
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  userInfo: UserInfo
}

/** 刷新Token响应数据 */
export interface RefreshResponse {
  accessToken: string
  expiresIn: number
}

/** 认证API */
export const authApi = {
  /**
   * 用户登录
   * POST /api/auth/login
   */
  login(params: { username: string; password: string }) {
    return request.post<ApiResponse<LoginResponse>>('/auth/login', params)
  },

  /**
   * 用户注册
   * POST /api/auth/register
   */
  register(params: { username: string; email: string; password: string }) {
    return request.post<ApiResponse<void>>('/auth/register', params)
  },

  /**
   * 刷新访问令牌
   * POST /api/auth/refresh
   */
  refresh(refreshToken: string) {
    return request.post<ApiResponse<RefreshResponse>>('/auth/refresh', { refreshToken })
  },

  /**
   * 用户登出
   * POST /api/auth/logout
   */
  logout(refreshToken: string) {
    return request.post<ApiResponse<void>>('/auth/logout', { refreshToken })
  },

  /**
   * 获取当前用户信息
   * GET /api/auth/me
   */
  getMe() {
    return request.get<ApiResponse<UserInfo>>('/auth/me')
  }
}
```

**验证：** TypeScript编译无错误（`npx vue-tsc --noEmit`），路由守卫逻辑完整。

---

### Task 4: 布局组件 + 导航
**Files:**
- Create: `agent-platform/frontend/src/layouts/AuthLayout.vue`
- Create: `agent-platform/frontend/src/layouts/MainLayout.vue`
- Create: `agent-platform/frontend/src/components/Sidebar.vue`
- Create: `agent-platform/frontend/src/components/AppHeader.vue`
- Create: `agent-platform/frontend/src/views/AgentHallView.vue`（占位）
- Create: `agent-platform/frontend/src/views/AgentDetailView.vue`（占位）
- Create: `agent-platform/frontend/src/views/WorkflowListView.vue`（占位）
- Create: `agent-platform/frontend/src/views/WorkflowEditorView.vue`（占位）

- [ ] **Step 1: 创建 layouts/AuthLayout.vue — 登录布局**

```vue
<template>
  <div class="auth-layout">
    <!-- 背景装饰层：渐变 + 粒子效果 -->
    <div class="auth-layout__bg">
      <div class="auth-layout__gradient"></div>
      <!-- CSS粒子 — 20个随机位置的小光点 -->
      <div class="auth-layout__particles">
        <span
          v-for="i in 20"
          :key="i"
          class="auth-layout__particle"
          :style="particleStyle(i)"
        ></span>
      </div>
    </div>

    <!-- 内容区域 — 居中卡片 -->
    <div class="auth-layout__content">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 生成每个粒子的随机位置和动画参数
 * 使用固定的种子（索引）确保每次渲染一致
 */
function particleStyle(index: number) {
  // 使用简单的伪随机，基于索引生成不同的位置和动画参数
  const seed = index * 137.5
  const left = (seed * 7.3 % 100)
  const top = (seed * 3.7 % 100)
  const size = 2 + (seed % 3)
  const duration = 6 + (index % 4) * 2
  const delay = (index % 5) * -2

  return {
    left: `${left}%`,
    top: `${top}%`,
    width: `${size}px`,
    height: `${size}px`,
    animationDuration: `${duration}s`,
    animationDelay: `${delay}s`
  }
}
</script>

<style lang="scss" scoped>
.auth-layout {
  position: relative;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

// 背景层
.auth-layout__bg {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.auth-layout__gradient {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 20% 50%, rgba(var(--color-primary-rgb), 0.08) 0%, transparent 50%),
    radial-gradient(ellipse at 80% 20%, rgba(var(--color-primary-rgb), 0.06) 0%, transparent 40%),
    radial-gradient(ellipse at 50% 80%, rgba(var(--color-primary-rgb), 0.04) 0%, transparent 60%),
    linear-gradient(180deg, var(--color-bg) 0%, var(--color-surface) 100%);
}

// 粒子效果
.auth-layout__particles {
  position: absolute;
  inset: 0;
}

.auth-layout__particle {
  position: absolute;
  border-radius: 50%;
  background: var(--color-primary);
  opacity: 0.3;
  animation: pulse-glow var(--duration-slower) var(--ease-in-out) infinite;

  // 每个粒子有不同的浮动动画
  @for $i from 1 through 20 {
    &:nth-child(#{$i}) {
      animation:
        pulse-glow #{6 + ($i % 4) * 2}s var(--ease-in-out) infinite,
        float #{8 + ($i % 3) * 3}s var(--ease-in-out) infinite;
    }
  }
}

// 内容区
.auth-layout__content {
  position: relative;
  z-index: 1;
  animation: fade-in 0.6s var(--ease-out);
}
</style>
```

- [ ] **Step 2: 创建 layouts/MainLayout.vue — 主布局**

```vue
<template>
  <div class="main-layout">
    <!-- 左侧侧栏 -->
    <Sidebar
      :collapsed="sidebarCollapsed"
      @toggle="toggleSidebar"
    />

    <!-- 右侧主区域 -->
    <div class="main-layout__right" :class="{ 'main-layout__right--expanded': sidebarCollapsed }">
      <!-- 顶部栏 -->
      <AppHeader @toggle-sidebar="toggleSidebar" />

      <!-- 主内容区 -->
      <main class="main-layout__content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import Sidebar from '@/components/Sidebar.vue'
import AppHeader from '@/components/AppHeader.vue'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { getStorage, setStorage, STORAGE_KEYS } from '@/utils/storage'

const authStore = useAuthStore()
const themeStore = useThemeStore()

// 侧栏折叠状态
const sidebarCollapsed = ref(getStorage<boolean>(STORAGE_KEYS.SIDEBAR_COLLAPSED) || false)

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  setStorage(STORAGE_KEYS.SIDEBAR_COLLAPSED, sidebarCollapsed.value)
}

// 初始化：获取用户信息 + 应用主题
onMounted(async () => {
  themeStore.initTheme()
  if (authStore.isLoggedIn && !authStore.userInfo) {
    await authStore.fetchUserInfo()
  }
})
</script>

<style lang="scss" scoped>
.main-layout {
  display: flex;
  width: 100vw;
  height: 100vh;
  overflow: hidden;
  background: var(--color-bg);
}

.main-layout__right {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin-left: var(--sidebar-width);
  transition: margin-left var(--duration-normal) var(--ease-in-out);

  &--expanded {
    margin-left: var(--sidebar-collapsed-width);
  }
}

.main-layout__content {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding: var(--spacing-6);
  background: var(--color-bg);
}

// 页面切换过渡
.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--duration-normal) var(--ease-in-out);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
```

- [ ] **Step 3: 创建 components/Sidebar.vue — 左侧导航栏**

```vue
<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <!-- Logo区域 -->
    <div class="sidebar__logo" @click="$router.push('/agents')">
      <div class="sidebar__logo-icon">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="28" height="28">
          <defs>
            <linearGradient id="logo-g" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" style="stop-color:var(--color-gradient-start)"/>
              <stop offset="100%" style="stop-color:var(--color-gradient-end)"/>
            </linearGradient>
          </defs>
          <rect width="32" height="32" rx="6" fill="url(#logo-g)"/>
          <text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-weight="bold" font-family="sans-serif">A</text>
        </svg>
      </div>
      <span v-show="!collapsed" class="sidebar__logo-text">Agent平台</span>
    </div>

    <!-- 导航列表 -->
    <nav class="sidebar__nav">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="sidebar__nav-item"
        :class="{ 'sidebar__nav-item--active': isNavItemActive(item.path) }"
      >
        <n-icon size="20" :component="item.icon" />
        <span v-show="!collapsed" class="sidebar__nav-label">{{ item.label }}</span>
      </router-link>
    </nav>

    <!-- 底部折叠按钮 -->
    <div class="sidebar__footer">
      <button class="sidebar__toggle" @click="$emit('toggle')">
        <n-icon size="18">
          <ChevronBackOutline v-if="!collapsed" />
          <ChevronForwardOutline v-else />
        </n-icon>
      </button>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { h } from 'vue'
import { useRoute } from 'vue-router'
import { NIcon } from 'naive-ui'
import {
  HomeOutline,
  GridOutline,
  GitBranchOutline,
  PulseOutline,
  SettingsOutline,
  ChevronBackOutline,
  ChevronForwardOutline
} from '@vicons/ionicons5'

defineProps<{
  collapsed: boolean
}>()

defineEmits<{
  toggle: []
}>()

const route = useRoute()

/** 导航项配置 */
const navItems = [
  { path: '/agents', label: 'Agent大厅', icon: GridOutline },
  { path: '/workflow', label: '工作流', icon: GitBranchOutline },
  { path: '/executions', label: '执行监控', icon: PulseOutline },
  { path: '/settings', label: '设置', icon: SettingsOutline }
]

/** 判断导航项是否处于激活状态 */
function isNavItemActive(path: string): boolean {
  return route.path.startsWith(path)
}
</script>

<style lang="scss" scoped>
.sidebar {
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  width: var(--sidebar-width);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border-light);
  display: flex;
  flex-direction: column;
  transition: width var(--duration-normal) var(--ease-in-out);
  z-index: 50;

  &--collapsed {
    width: var(--sidebar-collapsed-width);
  }
}

// Logo区域
.sidebar__logo {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-4) var(--spacing-4);
  height: var(--header-height);
  cursor: pointer;
  border-bottom: 1px solid var(--color-border-light);
}

.sidebar__logo-icon {
  flex-shrink: 0;
}

.sidebar__logo-text {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  white-space: nowrap;
  overflow: hidden;
}

// 导航列表
.sidebar__nav {
  flex: 1;
  padding: var(--spacing-2);
  overflow-y: auto;
}

.sidebar__nav-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-2) var(--spacing-3);
  margin-bottom: 2px;
  border-radius: var(--radius-base);
  color: var(--color-text-secondary);
  text-decoration: none;
  transition: all var(--duration-instant) var(--ease-in-out);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;

  &:hover {
    color: var(--color-text-primary);
    background: var(--color-primary-light);
  }

  &--active {
    color: var(--color-primary);
    background: var(--color-primary-light);
    font-weight: var(--font-weight-medium);
  }
}

.sidebar__nav-label {
  font-size: var(--font-size-base);
  overflow: hidden;
  text-overflow: ellipsis;
}

// 底部折叠按钮
.sidebar__footer {
  padding: var(--spacing-2);
  border-top: 1px solid var(--color-border-light);
}

.sidebar__toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: var(--spacing-2);
  border: none;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-text-primary);
    background: var(--color-primary-light);
  }
}
</style>
```

- [ ] **Step 4: 创建 components/AppHeader.vue — 顶部栏**

```vue
<template>
  <header class="app-header">
    <!-- 左侧：折叠按钮 + 面包屑 -->
    <div class="app-header__left">
      <button class="app-header__menu-btn" @click="$emit('toggle-sidebar')">
        <n-icon size="20" :component="MenuOutline" />
      </button>
      <span class="app-header__page-title">{{ pageTitle }}</span>
    </div>

    <!-- 右侧：搜索 + 主题切换 + 用户 -->
    <div class="app-header__right">
      <!-- 搜索框（占位） -->
      <n-input
        class="app-header__search"
        placeholder="搜索..."
        size="small"
        clearable
        round
      >
        <template #prefix>
          <n-icon :component="SearchOutline" />
        </template>
      </n-input>

      <!-- 主题切换按钮 -->
      <n-tooltip trigger="hover">
        <template #trigger>
          <button class="app-header__icon-btn" @click="showThemeSwitcher = true">
            <n-icon size="18" :component="ColorPaletteOutline" />
          </button>
        </template>
        切换主题
      </n-tooltip>

      <!-- 用户头像 + 下拉菜单 -->
      <n-dropdown :options="userMenuOptions" @select="handleUserMenu">
        <div class="app-header__user">
          <n-avatar
            :size="32"
            round
            :style="{ background: `linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end))` }"
          >
            {{ userInitial }}
          </n-avatar>
          <span class="app-header__username">{{ authStore.userInfo?.username }}</span>
        </div>
      </n-dropdown>
    </div>

    <!-- 主题切换弹窗 -->
    <ThemeSwitcher v-model:show="showThemeSwitcher" />
  </header>
</template>

<script setup lang="ts">
import { ref, computed, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon, NInput, NTooltip, NDropdown, NAvatar } from 'naive-ui'
import {
  MenuOutline,
  SearchOutline,
  ColorPaletteOutline,
  PersonOutline,
  LogOutOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import ThemeSwitcher from '@/components/ThemeSwitcher.vue'

defineEmits<{
  toggleSidebar: []
}>()

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const showThemeSwitcher = ref(false)

/** 页面标题（从路由meta获取） */
const pageTitle = computed(() => (route.meta.title as string) || '多Agent智能体平台')

/** 用户名首字母 */
const userInitial = computed(() => {
  const name = authStore.userInfo?.username || 'U'
  return name.charAt(0).toUpperCase()
})

/** 用户下拉菜单选项 */
const userMenuOptions = [
  {
    label: '个人信息',
    key: 'profile',
    icon: () => h(NIcon, null, { default: () => h(PersonOutline) })
  },
  {
    type: 'divider',
    key: 'd1'
  },
  {
    label: '退出登录',
    key: 'logout',
    icon: () => h(NIcon, null, { default: () => h(LogOutOutline) })
  }
]

/** 处理用户菜单选择 */
async function handleUserMenu(key: string) {
  if (key === 'logout') {
    await authStore.logout()
    router.push('/login')
  }
}
</script>

<style lang="scss" scoped>
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-height);
  padding: 0 var(--spacing-6);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border-light);
  flex-shrink: 0;
  backdrop-filter: blur(12px);
  background: rgba(var(--color-primary-rgb), 0.03);
}

// 左侧
.app-header__left {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

.app-header__menu-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-text-primary);
    background: var(--color-primary-light);
  }
}

.app-header__page-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
}

// 右侧
.app-header__right {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
}

.app-header__search {
  width: 200px;
}

.app-header__icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  border-radius: var(--radius-base);
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--duration-instant) var(--ease-in-out);

  &:hover {
    color: var(--color-primary);
    background: var(--color-primary-light);
  }
}

// 用户头像
.app-header__user {
  display: flex;
  align-items: center;
  gap: var(--spacing-2);
  cursor: pointer;
  padding: var(--spacing-1) var(--spacing-2);
  border-radius: var(--radius-base);
  transition: background var(--duration-instant);

  &:hover {
    background: var(--color-primary-light);
  }
}

.app-header__username {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}
</style>
```

- [ ] **Step 5: 创建4个占位视图页面**

创建 `views/AgentHallView.vue`：

```vue
<template>
  <div class="page-placeholder">
    <n-icon size="48" :component="GridOutline" />
    <h2>Agent 大厅</h2>
    <p>此页面将在后续Plan中实现</p>
  </div>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { GridOutline } from '@vicons/ionicons5'
</script>

<style lang="scss" scoped>
.page-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: var(--color-text-tertiary);
  gap: var(--spacing-3);

  h2 {
    color: var(--color-text-secondary);
    font-size: var(--font-size-xl);
  }

  p {
    font-size: var(--font-size-sm);
  }
}
</style>
```

创建 `views/AgentDetailView.vue`：

```vue
<template>
  <div class="page-placeholder">
    <n-icon size="48" :component="PersonOutline" />
    <h2>Agent 详情</h2>
    <p>此页面将在后续Plan中实现</p>
  </div>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { PersonOutline } from '@vicons/ionicons5'
</script>

<style lang="scss" scoped>
.page-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: var(--color-text-tertiary);
  gap: var(--spacing-3);

  h2 {
    color: var(--color-text-secondary);
    font-size: var(--font-size-xl);
  }

  p {
    font-size: var(--font-size-sm);
  }
}
</style>
```

创建 `views/WorkflowListView.vue`：

```vue
<template>
  <div class="page-placeholder">
    <n-icon size="48" :component="GitBranchOutline" />
    <h2>工作流列表</h2>
    <p>此页面将在后续Plan中实现</p>
  </div>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { GitBranchOutline } from '@vicons/ionicons5'
</script>

<style lang="scss" scoped>
.page-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: var(--color-text-tertiary);
  gap: var(--spacing-3);

  h2 {
    color: var(--color-text-secondary);
    font-size: var(--font-size-xl);
  }

  p {
    font-size: var(--font-size-sm);
  }
}
</style>
```

创建 `views/WorkflowEditorView.vue`：

```vue
<template>
  <div class="page-placeholder">
    <n-icon size="48" :component="CreateOutline" />
    <h2>工作流编辑器</h2>
    <p>此页面将在后续Plan中实现</p>
  </div>
</template>

<script setup lang="ts">
import { NIcon } from 'naive-ui'
import { CreateOutline } from '@vicons/ionicons5'
</script>

<style lang="scss" scoped>
.page-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  color: var(--color-text-tertiary);
  gap: var(--spacing-3);

  h2 {
    color: var(--color-text-secondary);
    font-size: var(--font-size-xl);
  }

  p {
    font-size: var(--font-size-sm);
  }
}
</style>
```

**验证：** 访问 `/login` 应显示登录布局。访问 `/agents`（需要先设置token）应显示主布局（侧栏+顶栏+内容区），导航项可点击，侧栏可折叠。

---

### Task 5: 登录页
**Files:**
- Create: `agent-platform/frontend/src/views/LoginView.vue`

- [ ] **Step 1: 创建 views/LoginView.vue — 登录页（含注册弹窗）**

```vue
<template>
  <AuthLayout>
    <div class="login-card">
      <!-- 卡片发光边框效果 -->
      <div class="login-card__glow"></div>

      <!-- Logo + 标题 -->
      <div class="login-card__header">
        <div class="login-card__logo">
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="40" height="40">
            <defs>
              <linearGradient id="login-logo-g" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" style="stop-color:var(--color-gradient-start)"/>
                <stop offset="100%" style="stop-color:var(--color-gradient-end)"/>
              </linearGradient>
            </defs>
            <rect width="32" height="32" rx="6" fill="url(#login-logo-g)"/>
            <text x="16" y="22" text-anchor="middle" fill="white" font-size="18" font-weight="bold" font-family="sans-serif">A</text>
          </svg>
        </div>
        <h1 class="login-card__title">多Agent智能体平台</h1>
        <p class="login-card__subtitle">登录您的账号以继续</p>
      </div>

      <!-- 登录表单 -->
      <n-form
        ref="loginFormRef"
        :model="loginForm"
        :rules="loginRules"
        @submit.prevent="handleLogin"
      >
        <!-- 用户名 -->
        <n-form-item path="username" label="用户名">
          <n-input
            v-model:value="loginForm.username"
            placeholder="请输入用户名"
            size="large"
            :input-props="{ autocomplete: 'username' }"
          >
            <template #prefix>
              <n-icon :component="PersonOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <!-- 密码 -->
        <n-form-item path="password" label="密码">
          <n-input
            v-model:value="loginForm.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码"
            size="large"
            :input-props="{ autocomplete: 'current-password' }"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <!-- 登录按钮 -->
        <n-button
          type="primary"
          block
          size="large"
          :loading="authStore.loading"
          attr-type="submit"
          class="login-card__submit"
        >
          登 录
        </n-button>
      </n-form>

      <!-- 注册链接 -->
      <div class="login-card__footer">
        <span class="login-card__hint">还没有账号？</span>
        <n-button text type="primary" @click="showRegisterModal = true">
          立即注册
        </n-button>
      </div>

      <!-- 底部主题切换 -->
      <div class="login-card__theme-area">
        <span class="login-card__theme-label">主题：</span>
        <div class="login-card__theme-options">
          <button
            v-for="theme in themeList"
            :key="theme.name"
            class="login-card__theme-btn"
            :class="{ 'login-card__theme-btn--active': themeStore.currentTheme === theme.name }"
            :title="theme.label"
            @click="themeStore.setTheme(theme.name)"
          >
            <span
              class="login-card__theme-swatch"
              :style="{ background: `linear-gradient(135deg, ${theme.colors.primary}, ${theme.colors.gradientEnd})` }"
            ></span>
          </button>
        </div>
      </div>
    </div>

    <!-- 注册弹窗 -->
    <n-modal
      v-model:show="showRegisterModal"
      preset="card"
      title="注册新账号"
      :style="{ maxWidth: '440px', width: '90vw' }"
      :bordered="false"
      :segmented="{ content: true }"
    >
      <n-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        @submit.prevent="handleRegister"
      >
        <n-form-item path="username" label="用户名">
          <n-input
            v-model:value="registerForm.username"
            placeholder="请输入用户名（3-20个字符）"
            size="large"
          >
            <template #prefix>
              <n-icon :component="PersonOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="email" label="邮箱">
          <n-input
            v-model:value="registerForm.email"
            placeholder="请输入邮箱地址"
            size="large"
          >
            <template #prefix>
              <n-icon :component="MailOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="password" label="密码">
          <n-input
            v-model:value="registerForm.password"
            type="password"
            show-password-on="click"
            placeholder="请输入密码（6-20个字符）"
            size="large"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-form-item path="confirmPassword" label="确认密码">
          <n-input
            v-model:value="registerForm.confirmPassword"
            type="password"
            show-password-on="click"
            placeholder="请再次输入密码"
            size="large"
          >
            <template #prefix>
              <n-icon :component="LockClosedOutline" color="var(--color-text-tertiary)" />
            </template>
          </n-input>
        </n-form-item>

        <n-button
          type="primary"
          block
          size="large"
          :loading="authStore.loading"
          attr-type="submit"
        >
          注册
        </n-button>
      </n-form>
    </n-modal>
  </AuthLayout>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import type { FormInst, FormRules } from 'naive-ui'
import {
  NForm, NFormItem, NInput, NButton, NIcon, NModal, useMessage
} from 'naive-ui'
import {
  PersonOutline, LockClosedOutline, MailOutline
} from '@vicons/ionicons5'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore, THEME_LIST } from '@/stores/theme'
import AuthLayout from '@/layouts/AuthLayout.vue'

const router = useRouter()
const route = useRoute()
const message = useMessage()
const authStore = useAuthStore()
const themeStore = useThemeStore()

// 初始化主题
themeStore.initTheme()

const themeList = THEME_LIST

// === 登录表单 ===
const loginFormRef = ref<FormInst | null>(null)
const loginForm = reactive({
  username: '',
  password: ''
})

const loginRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少6个字符', trigger: 'blur' }
  ]
}

// === 注册表单 ===
const showRegisterModal = ref(false)
const registerFormRef = ref<FormInst | null>(null)
const registerForm = reactive({
  username: '',
  email: '',
  password: '',
  confirmPassword: ''
})

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度3-20个字符', trigger: 'blur' }
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '请输入有效的邮箱地址', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度6-20个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule, value) => {
        if (value !== registerForm.password) {
          return new Error('两次输入的密码不一致')
        }
        return true
      },
      trigger: 'blur'
    }
  ]
}

// === 处理登录 ===
async function handleLogin() {
  try {
    await loginFormRef.value?.validate()
  } catch {
    return
  }

  try {
    await authStore.login({
      username: loginForm.username,
      password: loginForm.password
    })

    message.success('登录成功')

    // 跳转到之前的页面或首页
    const redirect = (route.query.redirect as string) || '/agents'
    router.push(redirect)
  } catch (error: any) {
    message.error(error.message || '登录失败，请检查用户名和密码')
  }
}

// === 处理注册 ===
async function handleRegister() {
  try {
    await registerFormRef.value?.validate()
  } catch {
    return
  }

  try {
    await authStore.register({
      username: registerForm.username,
      email: registerForm.email,
      password: registerForm.password
    })

    message.success('注册成功，请登录')
    showRegisterModal.value = false

    // 将注册的用户名自动填入登录表单
    loginForm.username = registerForm.username

    // 清空注册表单
    registerForm.username = ''
    registerForm.email = ''
    registerForm.password = ''
    registerForm.confirmPassword = ''
  } catch (error: any) {
    message.error(error.message || '注册失败')
  }
}
</script>

<style lang="scss" scoped>
// 登录卡片
.login-card {
  position: relative;
  width: 400px;
  max-width: 90vw;
  padding: var(--spacing-8) var(--spacing-8) var(--spacing-6);
  border-radius: var(--radius-lg);
  background: rgba(var(--color-primary-rgb), 0.03);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid var(--color-border);
  animation: fade-in 0.6s var(--ease-out);
}

// 发光边框效果
.login-card__glow {
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  background: linear-gradient(
    135deg,
    rgba(var(--color-primary-rgb), 0.3),
    transparent 50%,
    rgba(var(--color-primary-rgb), 0.1)
  );
  z-index: -1;
  filter: blur(1px);
}

// 头部
.login-card__header {
  text-align: center;
  margin-bottom: var(--spacing-6);
}

.login-card__logo {
  display: flex;
  justify-content: center;
  margin-bottom: var(--spacing-3);
}

.login-card__title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-1);
}

.login-card__subtitle {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

// 登录按钮
.login-card__submit {
  margin-top: var(--spacing-4);
  height: 44px;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
  border-radius: var(--radius-base);
  background: linear-gradient(135deg, var(--color-gradient-start), var(--color-gradient-end));
  border: none;
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    box-shadow: var(--shadow-primary);
    transform: translateY(-1px);
  }

  &:active {
    transform: translateY(0);
  }
}

// 底部
.login-card__footer {
  text-align: center;
  margin-top: var(--spacing-4);
}

.login-card__hint {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

// 主题选择区域
.login-card__theme-area {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-2);
  margin-top: var(--spacing-5);
  padding-top: var(--spacing-4);
  border-top: 1px solid var(--color-border-light);
}

.login-card__theme-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.login-card__theme-options {
  display: flex;
  gap: var(--spacing-2);
}

.login-card__theme-btn {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid transparent;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
  transition: all var(--duration-instant) var(--ease-in-out);

  &--active {
    border-color: var(--color-primary);
    box-shadow: 0 0 8px rgba(var(--color-primary-rgb), 0.4);
  }

  &:hover {
    transform: scale(1.15);
  }
}

.login-card__theme-swatch {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 50%;
}
</style>
```

**验证：** 访问 `/login` 应显示暗色背景 + 粒子动画 + 居中玻璃拟态登录卡片。输入用户名/密码提交后调用后端API登录。点击"立即注册"弹出注册弹窗。底部3个主题色块可切换主题色。

---

### Task 6: 主题切换组件
**Files:**
- Create: `agent-platform/frontend/src/components/ThemeSwitcher.vue`
- Create: `agent-platform/frontend/src/composables/useTheme.ts`

- [ ] **Step 1: 创建 components/ThemeSwitcher.vue — 主题切换弹窗**

```vue
<template>
  <n-modal
    :show="show"
    preset="card"
    title="选择主题"
    :style="{ maxWidth: '400px', width: '90vw' }"
    :bordered="false"
    :mask-closable="true"
    @update:show="$emit('update:show', $event)"
  >
    <div class="theme-switcher">
      <div
        v-for="theme in themeList"
        :key="theme.name"
        class="theme-switcher__item"
        :class="{ 'theme-switcher__item--active': currentTheme === theme.name }"
        @click="selectTheme(theme.name)"
      >
        <!-- 选中指示器 -->
        <div class="theme-switcher__indicator">
          <div
            v-if="currentTheme === theme.name"
            class="theme-switcher__check"
          >
            <n-icon size="14" :component="CheckmarkOutline" color="#fff" />
          </div>
        </div>

        <!-- 色块预览 -->
        <div class="theme-switcher__preview">
          <div
            class="theme-switcher__swatch"
            :style="{ background: theme.colors.bg }"
          >
            <span
              class="theme-switcher__swatch-accent"
              :style="{ background: `linear-gradient(135deg, ${theme.colors.gradientStart}, ${theme.colors.gradientEnd})` }"
            ></span>
          </div>
        </div>

        <!-- 主题信息 -->
        <div class="theme-switcher__info">
          <div class="theme-switcher__name">{{ theme.label }}</div>
          <div class="theme-switcher__desc">{{ theme.description }}</div>
        </div>
      </div>
    </div>
  </n-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { NModal, NIcon } from 'naive-ui'
import { CheckmarkOutline } from '@vicons/ionicons5'
import { useThemeStore, THEME_LIST, type ThemeName } from '@/stores/theme'

const props = defineProps<{
  show: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
}>()

const themeStore = useThemeStore()
const currentTheme = computed(() => themeStore.currentTheme)
const themeList = THEME_LIST

/** 选择主题 */
function selectTheme(name: ThemeName) {
  themeStore.setTheme(name)
  // 选择后自动关闭弹窗
  emit('update:show', false)
}
</script>

<style lang="scss" scoped>
.theme-switcher {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-3);
  padding: var(--spacing-2) 0;
}

.theme-switcher__item {
  display: flex;
  align-items: center;
  gap: var(--spacing-3);
  padding: var(--spacing-3) var(--spacing-4);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-in-out);

  &:hover {
    border-color: var(--color-primary);
    background: var(--color-primary-light);
  }

  &--active {
    border-color: var(--color-primary);
    background: var(--color-primary-light);
  }
}

// 选中指示器
.theme-switcher__indicator {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.theme-switcher__check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

// 色块预览
.theme-switcher__preview {
  flex-shrink: 0;
}

.theme-switcher__swatch {
  width: 48px;
  height: 32px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.theme-switcher__swatch-accent {
  width: 24px;
  height: 16px;
  border-radius: 3px;
}

// 主题信息
.theme-switcher__info {
  flex: 1;
  min-width: 0;
}

.theme-switcher__name {
  font-size: var(--font-size-base);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  margin-bottom: 2px;
}

.theme-switcher__desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
```

- [ ] **Step 2: 创建 composables/useTheme.ts — 主题切换composable**

```typescript
// ============================================================
// 主题切换Composable
// 封装主题初始化和切换逻辑，供组件使用
// ============================================================

import { ref, onMounted } from 'vue'
import { useThemeStore, type ThemeName } from '@/stores/theme'

/**
 * 主题切换composable
 *
 * 使用方式：
 * ```vue
 * <script setup>
 * const { currentTheme, setTheme } = useTheme()
 * </script>
 * ```
 */
export function useTheme() {
  const themeStore = useThemeStore()

  const currentTheme = ref<ThemeName>(themeStore.currentTheme)

  /**
   * 设置主题
   * @param theme 主题名称
   */
  function setTheme(theme: ThemeName) {
    themeStore.setTheme(theme)
    currentTheme.value = theme
  }

  /**
   * 初始化主题
   * 从localStorage读取并应用到DOM
   */
  function initTheme() {
    themeStore.initTheme()
    currentTheme.value = themeStore.currentTheme
  }

  // 组件挂载时自动初始化主题
  onMounted(() => {
    initTheme()
  })

  return {
    currentTheme,
    setTheme,
    initTheme
  }
}
```

**验证：** 在主布局顶栏点击主题图标，弹出3个主题选项卡片。点击切换后整个页面色值即时变化。刷新页面后主题保持不变。

---

### Task 7: 提交前端代码

- [ ] **Step 1: 安装依赖并验证构建**

```bash
cd e:\workspace\agent-platform\frontend
npm install
npm run dev    # 验证开发服务器启动
# 另开终端验证TypeScript编译
npx vue-tsc --noEmit
```

- [ ] **Step 2: 提交代码到Git**

```bash
cd e:\workspace
git add agent-platform/frontend/
git commit -m "feat: 搭建前端项目 - Vue3+TS+Vite+Naive UI，含登录页、主题系统、布局框架

- 初始化Vue3+TypeScript+Vite5项目，配置API代理和路径别名
- 实现3套暗色主题（Deep Space/Dark Pro/Cyber Glow），CSS变量驱动
- 完成Pinia状态管理（auth/theme stores）和Vue Router路由守卫
- 封装Axios实例，支持JWT自动注入和401刷新Token
- 实现登录页（玻璃拟态卡片+粒子动画）和注册弹窗
- 实现主布局（可折叠侧栏+顶部栏）和主题切换组件
- 预留4个占位视图页面（Agent大厅/详情/工作流列表/编辑器）"
```

---

## 验证清单

| 检查项 | 预期结果 |
|--------|----------|
| `npm run dev` 启动 | 开发服务器在5173端口启动，无报错 |
| `npx vue-tsc --noEmit` | TypeScript编译通过，无类型错误 |
| 访问 `/login` | 暗色背景+粒子动画+居中玻璃拟态登录卡片 |
| 登录表单验证 | 空提交显示错误提示，密码不足6位显示提示 |
| 登录流程 | 输入admin/admin123，登录成功跳转/agents |
| 注册弹窗 | 点击"立即注册"弹出模态框，含4个字段+确认密码校验 |
| 主题切换 | 点击底部色块/顶栏主题图标，页面色值即时变化 |
| 主题持久化 | 切换主题后刷新页面，主题保持不变 |
| 主布局 | 侧栏导航4项，当前路由高亮，可折叠/展开 |
| 顶部栏 | 显示页面标题+搜索框+主题按钮+用户头像+下拉菜单 |
| 路由守卫 | 未登录访问/agents重定向到/login，已登录访问/login重定向到/agents |
| API代理 | 浏览器请求/api/*被代理到localhost:8080 |
| 占位页面 | /agents, /agents/:id, /workflow, /workflow/:id 显示占位内容 |

---

## 与后续Plan的衔接

| 后续Plan | 本Plan提供的接口/组件 |
|----------|----------------------|
| Plan 4: Agent管理 | `api/request.ts` Axios实例、`stores/auth.ts` 权限检查、`MainLayout` 布局 |
| Plan 5: 工作流编辑器 | `WorkflowEditorView.vue` 占位页、CSS变量中的画布色值、`Sidebar` 导航 |
| Plan 6: 执行监控 | `Sidebar` 中预留的"执行监控"导航项、`PulseOutline` 图标 |
