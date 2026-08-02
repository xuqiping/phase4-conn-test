/// <reference types="vite/client" />

// 声明 .vue 模块，让TypeScript识别Vue单文件组件
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
